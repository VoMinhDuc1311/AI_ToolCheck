package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DockerfileSource;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Slf4j
public class DockerRuntimeOrchestrator implements RuntimeOrchestratorStrategy {

    private static final List<String> HEALTH_PROBE_PATHS = List.of("/actuator/health", "/health", "/healthz", "/");
    private static final Pattern SAFE_DOCKER_REF = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");
    private static final int CONTAINER_NAME_MAX = 63;

    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final RuntimeSourceMaterializer runtimeSourceMaterializer;
    private final RuntimeAutoProperties properties;
    private CommandExecutor commandExecutor = new ProcessCommandExecutor();
    private HealthProbe healthProbe = new HttpHealthProbe();

    @Override
    public SourceRuntime start(SourceProject project, RuntimeDetectionResult detection) {
        return start(project, detection, BuildStrategy.AUTO);
    }


    public SourceRuntime start(SourceProject project, RuntimeDetectionResult detection, BuildStrategy strategy) {
        BuildStrategy effective = strategy != null ? strategy : BuildStrategy.AUTO;
        log.info("[DockerRuntimeOrchestrator] start project={} strategy={}", project.getId(), effective);

        SourceRuntime runtime = sourceRuntimeRepository.save(buildRuntime(project, detection, RuntimeStatus.BUILDING, null));
        runtime.setBuildStartedAt(LocalDateTime.now());
        runtime.setBuildStrategyRequested(effective);
        sourceRuntimeRepository.save(runtime);

        MaterializedRuntimeSource materialized;
        try {
            materialized = runtimeSourceMaterializer.materialize(project.getId());
            if (materialized.getSourceVersionId() != null) {
                runtime.setSourceVersion(com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion.builder()
                        .id(materialized.getSourceVersionId())
                        .build());
            }
        } catch (Exception e) {
            return failRuntime(runtime, RuntimeStatus.BUILD_FAILED, "Source materialization failed: " + safeMessage(e));
        }

        String containerName = null;
        String imageTag = null;
        try {
            int appPort = detection.getDetectedPort() != null ? detection.getDetectedPort() : properties.getInternalPort();
            Path effectiveBuildRoot = resolveEffectiveBuildRoot(materialized.getRootDir(), detection);

            imageTag = sanitizeDockerRef(properties.getContainerPrefix()) + ":" + sanitizeDockerRef(project.getId() + "-" + System.currentTimeMillis());
            containerName = sanitizeContainerName(properties.getContainerPrefix(), project.getId(), System.currentTimeMillis());
            int hostPort = allocatePort(project.getId());

            // ── Dockerfile selection by strategy ──────────────────────────────
            DockerBuildPlan plan = selectDockerfile(effectiveBuildRoot, detection, appPort, effective);
            log.info("[DockerRuntimeOrchestrator] DockerBuildPlan: source={} file={}",
                    plan.source(), plan.dockerfilePath());

            runtime.setBuildStrategyUsed(effective);
            runtime.setDockerfileSource(plan.source());

            CommandResult build = runDockerBuild(effectiveBuildRoot, imageTag, plan.dockerfilePath());

            // ── AUTO_WITH_FALLBACK: if uploaded fails, retry with generated ──
            if (!build.success() && effective == BuildStrategy.AUTO_WITH_FALLBACK
                    && plan.source() == DockerfileSource.UPLOADED) {
                String originalError = build.summary();
                log.warn("[DockerRuntimeOrchestrator] Uploaded Dockerfile failed (strategy=AUTO_WITH_FALLBACK), "
                        + "falling back to generated Dockerfile. exitCode={}", build.exitCode());
                Path generatedDockerfile = generateDockerfileToFile(effectiveBuildRoot, detection, appPort);
                build = runDockerBuild(effectiveBuildRoot, imageTag, generatedDockerfile);
                runtime.setDockerfileSource(DockerfileSource.GENERATED);
                String fallbackReason = "Uploaded Dockerfile failed (exitCode=" + build.exitCode()
                        + "). Fell back to generated Dockerfile. Original error: " + tail(originalError, 500);
                runtime.setFallbackReason(fallbackReason);
                log.info("[DockerRuntimeOrchestrator] Fallback build result: success={}", build.success());
            }

            if (!build.success()) {
                cleanupContainerAndImage(containerName, imageTag);
                String suggestion = plan.source() == DockerfileSource.UPLOADED
                        ? " Uploaded Dockerfile failed. Use buildStrategy=GENERATED_DOCKERFILE or "
                          + "AUTO_WITH_FALLBACK to let AI ToolCheck generate a correct Dockerfile."
                        : "";
                return failRuntime(runtime, RuntimeStatus.BUILD_FAILED,
                        "docker build failed: " + build.summary() + suggestion);
            }

            runtime.setBuildFinishedAt(LocalDateTime.now());
            runtime.setImageName(imageTag);
            runtime.setRuntimeStatus(RuntimeStatus.STARTING);
            sourceRuntimeRepository.save(runtime);

            CommandResult run = runDockerContainer(imageTag, containerName, hostPort, appPort, properties.getDockerNetwork());
            if (!run.success()) {
                cleanupContainerAndImage(containerName, imageTag);
                return failRuntime(runtime, RuntimeStatus.UNHEALTHY, "docker run failed: " + run.summary());
            }

            String publicUrl = buildPublicUrl(hostPort, detection.getContextPath());
            runtime.setContainerName(containerName);
            runtime.setInternalPort(appPort);
            runtime.setDetectedPort(appPort);
            runtime.setDockerNetwork(properties.getDockerNetwork());
            runtime.setPublicBaseUrl(publicUrl);
            sourceRuntimeRepository.save(runtime);

            ProbeResult probe = pollUntilHealthy(project.getId(), publicUrl, properties.getStartTimeoutSeconds());
            if (!probe.up()) {
                cleanupContainerAndImage(containerName, imageTag);
                return failRuntime(runtime, RuntimeStatus.UNHEALTHY,
                        "Container started but health probe failed: " + probe.status());
            }

            runtime.setRuntimeStatus(RuntimeStatus.UP);
            runtime.setStartedAt(LocalDateTime.now());
            runtime.setHealthCheckPath(probe.path());
            runtime.setLastHealthStatus(probe.status());
            runtime.setLastError(null);
            return sourceRuntimeRepository.save(runtime);
        } catch (Exception e) {
            cleanupContainerAndImage(containerName, imageTag);
            return failRuntime(runtime, RuntimeStatus.BUILD_FAILED, safeMessage(e));
        } finally {
            cleanupMaterialized(materialized);
        }
    }

    @Override
    public SourceRuntime stop(SourceRuntime runtime) {
        if (runtime.getRuntimeStatus() == RuntimeStatus.STOPPED) {
            return runtime;
        }
        runtime.setRuntimeStatus(RuntimeStatus.STOPPING);
        sourceRuntimeRepository.save(runtime);
        if (hasText(runtime.getContainerName())) {
            commandExecutor.run(30, List.of("docker", "rm", "-f", runtime.getContainerName()));
        }
        runtime.setRuntimeStatus(RuntimeStatus.STOPPED);
        runtime.setStoppedAt(LocalDateTime.now());
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    public String name() {
        return "DockerRuntimeOrchestrator";
    }

    void setCommandExecutor(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    void setHealthProbe(HealthProbe healthProbe) {
        this.healthProbe = healthProbe;
    }


    Path resolveEffectiveBuildRoot(Path materializedRoot, RuntimeDetectionResult detection) throws IOException {
        String projectRoot = detection.getProjectRoot();
        if (projectRoot == null || projectRoot.isBlank()) {
            return materializedRoot;
        }
        Path candidate = materializedRoot.resolve(projectRoot).normalize();
        if (!candidate.startsWith(materializedRoot)) {
            throw new IOException("Detected project root escapes materialized root: " + projectRoot);
        }
        if (!Files.isDirectory(candidate)) {
            throw new IOException("Detected project root does not exist as a directory: " + candidate);
        }
        log.info("[DockerRuntimeOrchestrator] Using nested project root as build context: {}", candidate);
        return candidate;
    }

    // ── Dockerfile selection by strategy ─────────────────────────────────────


    DockerBuildPlan selectDockerfile(Path buildRoot, RuntimeDetectionResult detection,
                                     int appPort, BuildStrategy strategy) throws IOException {
        Path uploadedDockerfile = buildRoot.resolve("Dockerfile");
        boolean hasUploaded = Files.isRegularFile(uploadedDockerfile);

        return switch (strategy) {
            case UPLOADED_DOCKERFILE_ONLY -> {
                if (!hasUploaded) {
                    throw new IOException(
                            "buildStrategy=UPLOADED_DOCKERFILE_ONLY but no Dockerfile found in project root. "
                            + "Upload a Dockerfile or use GENERATED_DOCKERFILE.");
                }
                yield new DockerBuildPlan(uploadedDockerfile, DockerfileSource.UPLOADED);
            }
            case GENERATED_DOCKERFILE -> {
                Path generated = generateDockerfileToFile(buildRoot, detection, appPort);
                yield new DockerBuildPlan(generated, DockerfileSource.GENERATED);
            }
            case AUTO, AUTO_WITH_FALLBACK -> {
                if (hasUploaded) {
                    yield new DockerBuildPlan(uploadedDockerfile, DockerfileSource.UPLOADED);
                }
                Path generated = generateDockerfileToFile(buildRoot, detection, appPort);
                yield new DockerBuildPlan(generated, DockerfileSource.GENERATED);
            }
        };
    }

    /** Writes the generated Dockerfile to {@code buildRoot/Dockerfile.autorun} and returns the path. */
    Path generateDockerfileToFile(Path buildRoot, RuntimeDetectionResult detection, int appPort) throws IOException {
        boolean hasMvnw    = Files.isRegularFile(buildRoot.resolve("mvnw"));
        boolean hasGradlew = Files.isRegularFile(buildRoot.resolve("gradlew"));
        Path generated = buildRoot.resolve("Dockerfile.autorun");
        Files.writeString(generated, generateDockerfile(detection, appPort, hasMvnw, hasGradlew));
        return generated;
    }

    /**
     * Generates a correct Dockerfile for the detected runtime type.
     *
     * <p><b>Maven rules:</b>
     * <ul>
     *   <li>With {@code mvnw}: use {@code eclipse-temurin:21-jdk} + {@code ./mvnw}.</li>
     *   <li>Without {@code mvnw}: use {@code maven:3.9-eclipse-temurin-21} — which ships Maven CLI.
     *       Never use {@code eclipse-temurin:21-jdk} alone when {@code mvn} CLI is needed.</li>
     * </ul>
     *
     * <p><b>Gradle rules:</b>
     * <ul>
     *   <li>With {@code gradlew}: use {@code eclipse-temurin:21-jdk} + {@code ./gradlew}.</li>
     *   <li>Without {@code gradlew}: use {@code gradle:8-jdk21}.</li>
     * </ul>
     */
    String generateDockerfile(RuntimeDetectionResult detection, int appPort,
                               boolean hasMvnw, boolean hasGradlew) {
        RuntimeType type = detection.getRuntimeType();
        if (type == RuntimeType.SPRING_BOOT_MAVEN) {
            if (hasMvnw) {
                return """
                        FROM eclipse-temurin:21-jdk AS build
                        WORKDIR /app
                        COPY mvnw .
                        COPY .mvn .mvn
                        COPY pom.xml .
                        COPY src ./src
                        RUN chmod +x mvnw
                        RUN ./mvnw -q -DskipTests clean package

                        FROM eclipse-temurin:21-jre
                        WORKDIR /app
                        COPY --from=build /app/target/*.jar app.jar
                        ENV SERVER_PORT=%d
                        EXPOSE %d
                        ENTRYPOINT ["java", "-jar", "app.jar"]
                        """.formatted(appPort, appPort);
            } else {
                // No mvnw — use the official Maven image which includes mvn CLI.
                // NEVER use eclipse-temurin:21-jdk alone here: mvn is not installed on that image.
                return """
                        FROM maven:3.9-eclipse-temurin-21 AS build
                        WORKDIR /app
                        COPY pom.xml .
                        COPY src ./src
                        RUN mvn -q -DskipTests clean package

                        FROM eclipse-temurin:21-jre
                        WORKDIR /app
                        COPY --from=build /app/target/*.jar app.jar
                        ENV SERVER_PORT=%d
                        EXPOSE %d
                        ENTRYPOINT ["java", "-jar", "app.jar"]
                        """.formatted(appPort, appPort);
            }
        }
        if (type == RuntimeType.SPRING_BOOT_GRADLE) {
            if (hasGradlew) {
                return """
                        FROM eclipse-temurin:21-jdk AS build
                        WORKDIR /app
                        COPY gradlew .
                        COPY gradle ./gradle
                        COPY build.gradle* .
                        COPY settings.gradle* .
                        COPY src ./src
                        RUN chmod +x gradlew
                        RUN ./gradlew -q -x test bootJar

                        FROM eclipse-temurin:21-jre
                        WORKDIR /app
                        COPY --from=build /app/build/libs/*.jar app.jar
                        ENV SERVER_PORT=%d
                        EXPOSE %d
                        ENTRYPOINT ["java", "-jar", "app.jar"]
                        """.formatted(appPort, appPort);
            } else {
                return """
                        FROM gradle:8-jdk21 AS build
                        WORKDIR /app
                        COPY build.gradle* .
                        COPY settings.gradle* .
                        COPY src ./src
                        RUN gradle -q -x test bootJar

                        FROM eclipse-temurin:21-jre
                        WORKDIR /app
                        COPY --from=build /app/build/libs/*.jar app.jar
                        ENV SERVER_PORT=%d
                        EXPOSE %d
                        ENTRYPOINT ["java", "-jar", "app.jar"]
                        """.formatted(appPort, appPort);
            }
        }
        throw new IllegalArgumentException("Unsupported runtime type for Docker build: " + type);
    }

    /** Legacy 2-arg overload used by existing tests; detects wrapper from filesystem. */
    String generateDockerfile(RuntimeDetectionResult detection, int appPort) {
        return generateDockerfile(detection, appPort, false, false);
    }

    /** Result of Dockerfile selection: which file to use and which source it is. */
    record DockerBuildPlan(Path dockerfilePath, DockerfileSource source) {}

    private static String tail(String s, int max) {
        if (s == null || s.isBlank()) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    CommandResult runDockerBuild(Path contextDir, String imageTag, Path dockerfilePath) {
        int timeout = properties.getBuildTimeoutSeconds();
        List<String> command = List.of(
                "docker", "build",
                "-f", dockerfilePath.toAbsolutePath().toString(),
                "-t", imageTag,
                contextDir.toAbsolutePath().toString()
        );
        log.info("[DockerRuntimeOrchestrator] docker build: context={} imageTag={} timeout={}s command={}",
                contextDir.toAbsolutePath(), imageTag, timeout, command);
        CommandResult result = commandExecutor.run(timeout, command);
        log.info("[DockerRuntimeOrchestrator] docker build finished: {}", result.diagnostic());
        return result;
    }

    CommandResult runDockerContainer(String imageTag, String containerName, int hostPort, int appPort, String network) {
        if (!hasText(network)) {
            return new CommandResult(false, 1, "Docker network is not configured.");
        }
        CommandResult networkCheck = commandExecutor.run(30, List.of("docker", "network", "inspect", network));
        if (!networkCheck.success()) {
            return new CommandResult(false, networkCheck.exitCode(), "Docker network missing: " + network);
        }
        return commandExecutor.run(30, List.of(
                "docker", "run", "-d",
                "--name", containerName,
                "-p", hostPort + ":" + appPort,
                "--network", network,
                "--restart", "no",
                imageTag
        ));
    }

    int allocatePort(UUID projectId) {
        int start = properties.getPortMin();
        int end = properties.getPortMax();
        if (start < 1 || end < start || end > 65535) {
            throw new IllegalStateException("Invalid runtime port range: " + start + "-" + end);
        }
        for (int port = start; port <= end; port++) {
            if (isPortAvailable(port)) {
                log.info("[DockerRuntimeOrchestrator] Allocated host port {} for project={}", port, projectId);
                return port;
            }
        }
        throw new IllegalStateException("No free host port in runtime range " + start + "-" + end);
    }

    boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    String sanitizeContainerName(String prefix, UUID projectId, long timestamp) {
        String raw = sanitizeDockerRef(prefix) + "-" + projectId.toString().substring(0, 8) + "-" + timestamp;
        return raw.length() <= CONTAINER_NAME_MAX ? raw : raw.substring(0, CONTAINER_NAME_MAX);
    }

    String sanitizeDockerRef(String raw) {
        String sanitized = (raw == null ? "aitc-runtime" : raw)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("[^a-z0-9]+$", "");
        if (sanitized.isBlank()) {
            sanitized = "aitc-runtime";
        }
        if (sanitized.length() > 128) {
            sanitized = sanitized.substring(0, 128);
        }
        if (!SAFE_DOCKER_REF.matcher(sanitized).matches()) {
            throw new IllegalArgumentException("Unsafe Docker reference after sanitization.");
        }
        return sanitized;
    }

    private String buildPublicUrl(int hostPort, String contextPath) {
        String path = hasText(contextPath) ? contextPath.trim() : "";
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (hasText(path) && !path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + properties.getPublicHost() + ":" + hostPort + path;
    }

    private ProbeResult pollUntilHealthy(UUID projectId, String publicUrl, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        List<ProbeCandidate> candidates = buildProbeCandidates(projectId);
        while (System.currentTimeMillis() < deadline) {
            for (ProbeCandidate candidate : candidates) {
                int status = healthProbe.get(publicUrl + candidate.path(), properties.getHealthTimeoutSeconds());
                if (status >= 200 && status < 300) {
                    String prefix = candidate.openapi() ? "UP:OPENAPI_PROBE:" : "UP:";
                    return new ProbeResult(true, candidate.path(), prefix + candidate.path() + ":" + status);
                }
            }
            sleep(3);
        }
        return new ProbeResult(false, null, "DOWN:no_healthy_endpoint");
    }

    private List<ProbeCandidate> buildProbeCandidates(UUID projectId) {
        List<ProbeCandidate> candidates = new ArrayList<>();
        HEALTH_PROBE_PATHS.forEach(path -> candidates.add(new ProbeCandidate(path, false)));
        try {
            apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrue(projectId).stream()
                    .filter(e -> e.getHttpMethod() == HttpMethod.GET)
                    .map(ApiEndpoint::getEndpointPath)
                    .filter(this::isSafeProbePath)
                    .distinct()
                    .limit(5)
                    .forEach(path -> candidates.add(new ProbeCandidate(path, true)));
        } catch (Exception e) {
            log.debug("[DockerRuntimeOrchestrator] Could not load OpenAPI probe candidates: {}", e.getMessage());
        }
        return candidates;
    }

    private boolean isSafeProbePath(String path) {
        return hasText(path)
                && path.startsWith("/")
                && !path.contains("{")
                && !path.contains("}")
                && !path.contains("..")
                && path.length() <= 200;
    }

    private SourceRuntime failRuntime(SourceRuntime runtime, RuntimeStatus status, String reason) {
        runtime.setRuntimeStatus(status);
        runtime.setBuildFinishedAt(LocalDateTime.now());
        runtime.setLastHealthStatus(status == RuntimeStatus.UNHEALTHY ? "DOWN:HEALTH_DOWN" : runtime.getLastHealthStatus());
        runtime.setLastError(reason);
        runtime.setPublicBaseUrl(null);
        return sourceRuntimeRepository.save(runtime);
    }

    private SourceRuntime buildRuntime(SourceProject project, RuntimeDetectionResult detection,
                                       RuntimeStatus status, String error) {
        return SourceRuntime.builder()
                .sourceProject(project)
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(status)
                .runtimeType(detection.getRuntimeType())
                .detectedPort(detection.getDetectedPort())
                .contextPath(detection.getContextPath())
                .lastError(error)
                .build();
    }

    private void cleanupContainerAndImage(String containerName, String imageTag) {
        if (hasText(containerName)) {
            commandExecutor.run(30, List.of("docker", "rm", "-f", containerName));
        }
        if (hasText(imageTag)) {
            commandExecutor.run(30, List.of("docker", "rmi", "-f", imageTag));
        }
    }

    private void cleanupMaterialized(MaterializedRuntimeSource materialized) {
        try {
            if (materialized != null) {
                materialized.cleanup();
            }
        } catch (RuntimeException e) {
            log.warn("[DockerRuntimeOrchestrator] Failed to clean up temp source: {}", e.getMessage());
        }
    }

    private void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    interface CommandExecutor {
        CommandResult run(int timeoutSeconds, List<String> command);
    }


    record CommandResult(boolean success, int exitCode, String output,
                         boolean timedOut, long elapsedMs,
                         String stdout, String stderr) {


        CommandResult(boolean success, int exitCode, String output) {
            this(success, exitCode, output, false, -1, output, "");
        }

        static final int TAIL_CHARS = 2_000;

        String summary() {
            String combined = buildCombinedOutput();
            String masked = combined.replaceAll("(?i)(token|password|secret|api[_-]?key)=\\S+", "$1=***");
            return masked.length() > 500 ? masked.substring(0, 500) : masked;
        }


        String diagnostic() {
            return String.format("exitCode=%d timedOut=%b elapsedMs=%d stdout=[%s] stderr=[%s]",
                    exitCode, timedOut, elapsedMs, tail(stdout, 300), tail(stderr, 300));
        }

        private String buildCombinedOutput() {
            if (timedOut) {
                return "Process timed out after " + elapsedMs + "ms. stdout=[" + tail(stdout, 300) + "] stderr=[" + tail(stderr, 300) + "]";
            }
            if (stderr != null && !stderr.isBlank() && !success) {
                return "exitCode=" + exitCode + " stdout=[" + tail(stdout, 200) + "] stderr=[" + tail(stderr, 200) + "]";
            }
            String text = output != null ? output : (stdout != null ? stdout : "");
            if (text.isBlank()) {
                return "exitCode=" + exitCode;
            }
            return text;
        }

        private static String tail(String s, int maxChars) {
            if (s == null || s.isBlank()) return "";
            return s.length() <= maxChars ? s : s.substring(s.length() - maxChars);
        }
    }

    interface HealthProbe {
        int get(String url, int timeoutSeconds);
    }

    record ProbeResult(boolean up, String path, String status) {
    }

    record ProbeCandidate(String path, boolean openapi) {
    }


    static class ProcessCommandExecutor implements CommandExecutor {

        private static final int TAIL_CHARS = CommandResult.TAIL_CHARS;

        @Override
        public CommandResult run(int timeoutSeconds, List<String> command) {
            long startMs = System.currentTimeMillis();
            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();
            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                // Keep stdout and stderr SEPARATE so we can tell warnings from errors.
                // Both are drained concurrently to prevent pipe-buffer deadlock.
                pb.redirectErrorStream(false);
                process = pb.start();

                // Drain stdout and stderr concurrently in daemon threads.
                // This MUST happen before waitFor() to avoid deadlock when output
                // fills the OS pipe buffer (typically 64 KB on Linux).
                final Process finalProcess = process;
                Thread stdoutDrainer = drainStream(finalProcess.getInputStream(), stdoutBuf, TAIL_CHARS);
                Thread stderrDrainer = drainStream(finalProcess.getErrorStream(), stderrBuf, TAIL_CHARS);
                stdoutDrainer.start();
                stderrDrainer.start();

                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                long elapsedMs = System.currentTimeMillis() - startMs;

                // Give drainer threads a moment to flush remaining buffered bytes
                stdoutDrainer.join(2_000);
                stderrDrainer.join(2_000);

                String stdoutTail = stdoutBuf.toString();
                String stderrTail = stderrBuf.toString();

                if (!finished) {
                    process.destroyForcibly();
                    String combined = "stdout=[" + tail(stdoutTail, 400) + "] stderr=[" + tail(stderrTail, 400) + "]";
                    return new CommandResult(false, -1,
                            "Process timed out after " + elapsedMs + "ms (limit=" + timeoutSeconds + "s). " + combined,
                            true, elapsedMs, stdoutTail, stderrTail);
                }

                int exit = process.exitValue();
                // Success = exit code 0 only. stderr content is irrelevant for success determination.
                String combined = stdoutTail + (stderrTail.isBlank() ? "" : "\n[stderr] " + stderrTail);
                return new CommandResult(exit == 0, exit, combined, false, elapsedMs, stdoutTail, stderrTail);

            } catch (IOException e) {
                long elapsedMs = System.currentTimeMillis() - startMs;
                return new CommandResult(false, -1, "IO error: " + e.getMessage(),
                        false, elapsedMs, "", "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process != null) {
                    process.destroyForcibly();
                }
                long elapsedMs = System.currentTimeMillis() - startMs;
                return new CommandResult(false, -1, "interrupted",
                        false, elapsedMs, "", "");
            }
        }

        
        private Thread drainStream(java.io.InputStream stream, StringBuilder buf, int maxChars) {
            Thread t = new Thread(() -> {
                try {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = stream.read(chunk)) != -1) {
                        buf.append(new String(chunk, 0, n));
                        // Bound the buffer to avoid unbounded memory growth on huge outputs
                        if (buf.length() > maxChars * 2) {
                            buf.delete(0, buf.length() - maxChars);
                        }
                    }
                } catch (IOException ignored) {
                    // Stream closed when process exits — expected
                }
            });
            t.setDaemon(true);
            return t;
        }

        private static String tail(String s, int maxChars) {
            if (s == null || s.isBlank()) return "";
            return s.length() <= maxChars ? s : s.substring(s.length() - maxChars);
        }
    }

    static class HttpHealthProbe implements HealthProbe {
        @Override
        public int get(String url, int timeoutSeconds) {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .GET()
                        .build();
                return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            } catch (Exception e) {
                return -1;
            }
        }
    }
}
