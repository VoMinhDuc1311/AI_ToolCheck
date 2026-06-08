package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DockerfileSource;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceUploadVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeLifecycleService;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import java.util.ArrayList;
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
    private final SourceUploadVersionRepository sourceUploadVersionRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final RuntimeSourceMaterializer runtimeSourceMaterializer;
    private final RuntimeAutoProperties properties;
    private final SourceRuntimeLifecycleService lifecycleService;
    private final RuntimeStartWorker runtimeStartWorker;

    private CommandExecutor commandExecutor = new ProcessCommandExecutor();
    private HealthProbe healthProbe = new HttpHealthProbe();

    @Override
    public SourceRuntime start(SourceProject project, RuntimeDetectionResult detection) {
        return start(project, detection, BuildStrategy.AUTO);
    }

    public SourceRuntime start(SourceProject project, RuntimeDetectionResult detection, BuildStrategy strategy) {
        BuildStrategy effective = strategy != null ? strategy : BuildStrategy.AUTO;
        log.info("[DockerRuntimeOrchestrator] start project={} strategy={} (async start triggered)", project.getId(), effective);

        UUID sourceVersionId = sourceUploadVersionRepository.findTopBySourceProjectIdOrderByVersionNoDesc(project.getId())
                .map(com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion::getId)
                .orElse(null);

        // 1. Create the building runtime record in a separate transaction
        SourceRuntime runtime = lifecycleService.createBuildingRuntime(project, sourceVersionId, effective);

        // 2. Delegate the build/run steps to the async worker
        try {
            runtimeStartWorker.runStartAsync(runtime.getId(), project, detection, effective, this);
        } catch (RuntimeException e) {
            log.error("[DockerRuntimeOrchestrator] Async dispatch failed project={} runtimeId={} strategy={}: {}",
                    project.getId(), runtime.getId(), effective, e.getMessage(), e);
            lifecycleService.markBuildFailed(runtime.getId(), "Runtime start dispatch failed: " + e.getMessage());
            var fresh = lifecycleService.findFresh(runtime.getId());
            return fresh == null ? runtime : fresh.orElse(runtime);
        }

        // 3. Return the BUILDING runtime record immediately
        return runtime;
    }

    public void runStartSynchronously(UUID runtimeId, SourceProject project, RuntimeDetectionResult detection, BuildStrategy strategy) {
        log.info("[DockerRuntimeOrchestrator] Starting synchronous runtime build for project={}, runtimeId={}, strategy={}",
                project.getId(), runtimeId, strategy);

        MaterializedRuntimeSource materialized = null;
        try {
            if (isCancelled(runtimeId, "before materialization")) {
                return;
            }
            materialized = runtimeSourceMaterializer.materialize(project.getId());
        } catch (Exception e) {
            log.error("[DockerRuntimeOrchestrator] Materialization failed for project={}, runtimeId={}: {}",
                    project.getId(), runtimeId, e.getMessage());
            if (!isCancelled(runtimeId, "after materialization failure")) {
                lifecycleService.markBuildFailed(runtimeId, "Source materialization failed: " + e.getMessage());
            }
            return;
        }

        String containerName = null;
        String imageTag = null;
        try {
            int appPort = detection.getDetectedPort() != null ? detection.getDetectedPort() : properties.getInternalPort();
            Path effectiveBuildRoot = resolveEffectiveBuildRoot(materialized.getRootDir(), detection);

            imageTag = sanitizeDockerRef(properties.getContainerPrefix()) + ":" + sanitizeDockerRef(project.getId() + "-" + System.currentTimeMillis());
            containerName = sanitizeContainerName(properties.getContainerPrefix(), project.getId(), System.currentTimeMillis());
            int hostPort = allocatePort(project.getId());

            // Select Dockerfile by strategy
            DockerRuntimeOrchestrator.DockerBuildPlan plan = selectDockerfile(effectiveBuildRoot, detection, appPort, strategy);
            log.info("[DockerRuntimeOrchestrator] DockerBuildPlan: source={} file={}", plan.source(), plan.dockerfilePath());

            if (isCancelled(runtimeId, "before docker build")) {
                cleanupContainerAndImage(containerName, imageTag);
                return;
            }
            DockerRuntimeOrchestrator.CommandResult build = runDockerBuild(effectiveBuildRoot, imageTag, plan.dockerfilePath());

            DockerfileSource dockerfileSourceUsed = plan.source();
            String fallbackReason = null;
            DockerRuntimeOrchestrator.CommandResult uploadedFailure = null;

            // AUTO_WITH_FALLBACK retry
            if (!build.success() && strategy == BuildStrategy.AUTO_WITH_FALLBACK && plan.source() == DockerfileSource.UPLOADED) {
                uploadedFailure = build;
                String originalError = build.summary();
                log.warn("[DockerRuntimeOrchestrator] Uploaded Dockerfile failed (strategy=AUTO_WITH_FALLBACK), falling back to generated. exitCode={}", build.exitCode());
                Path generatedDockerfile = generateDockerfileToFile(effectiveBuildRoot, detection, appPort);
                if (isCancelled(runtimeId, "before generated fallback build")) {
                    cleanupContainerAndImage(containerName, imageTag);
                    return;
                }
                build = runDockerBuild(effectiveBuildRoot, imageTag, generatedDockerfile);
                dockerfileSourceUsed = DockerfileSource.GENERATED;
                fallbackReason = "Uploaded Dockerfile failed: exitCode=" + uploadedFailure.exitCode()
                        + " " + DockerRuntimeOrchestrator.tail(originalError, 500)
                        + "; fallback generated Dockerfile used.";
            }

            if (!build.success()) {
                cleanupContainerAndImage(containerName, imageTag);
                if (isCancelled(runtimeId, "after docker build failure")) {
                    return;
                }
                String suggestion = plan.source() == DockerfileSource.UPLOADED
                        ? " Uploaded Dockerfile failed. Use buildStrategy=GENERATED_DOCKERFILE or AUTO_WITH_FALLBACK."
                        : "";
                String error = uploadedFailure == null
                        ? "docker build failed: " + build.summary() + suggestion
                        : "docker build failed after fallback. Uploaded Dockerfile failed: "
                        + uploadedFailure.summary() + " Generated Dockerfile failed: " + build.summary();
                lifecycleService.markBuildFailed(runtimeId, error);
                return;
            }

            if (isCancelled(runtimeId, "after docker build")) {
                cleanupContainerAndImage(containerName, imageTag);
                return;
            }
            // Mark STARTING
            lifecycleService.markStarting(runtimeId, containerName, imageTag, dockerfileSourceUsed, strategy, fallbackReason, LocalDateTime.now());

            if (isCancelled(runtimeId, "before docker run")) {
                cleanupContainerAndImage(containerName, imageTag);
                return;
            }
            // Run container (with labels)
            DockerRuntimeOrchestrator.CommandResult run = runDockerContainer(runtimeId, project.getId(), imageTag, containerName, hostPort, appPort, properties.getDockerNetwork());
            if (!run.success()) {
                cleanupContainerAndImage(containerName, imageTag);
                if (isCancelled(runtimeId, "after docker run failure")) {
                    return;
                }
                lifecycleService.markStartFailed(runtimeId, "docker run failed: " + run.summary());
                return;
            }

            if (isCancelled(runtimeId, "before health polling")) {
                cleanupContainerAndImage(containerName, imageTag);
                return;
            }

            // --- URL separation ---
            // publicBaseUrl: used for client-facing access and persisted to DB only after health passes.
            // internalHealthBaseUrl: used exclusively for startup health probing over the Docker internal network.
            // Backend and runtime containers share the same Docker network; probing via the container's
            // internal name/port is direct and reliable — the public EC2 IP/port goes through NAT and may
            // be blocked by security groups or routing policies, causing false DOWN:timeout results.
            String publicBaseUrl = buildPublicUrl(hostPort, detection.getContextPath());
            String internalHealthBaseUrl = buildInternalHealthUrl(containerName, appPort);
            log.info("[DockerRuntimeOrchestrator] Startup health probe internalBaseUrl={} publicBaseUrl={}",
                    internalHealthBaseUrl, publicBaseUrl);

            DockerRuntimeOrchestrator.ProbeResult probe = pollUntilHealthy(runtimeId, project.getId(), internalHealthBaseUrl, properties.getStartupTimeoutSeconds());
            if (!probe.up()) {
                cleanupContainerAndImage(containerName, imageTag);
                if (isCancelled(runtimeId, "after health probe failure")) {
                    return;
                }
                lifecycleService.markUnhealthy(runtimeId, probe.status(), "Container started but health probe failed: " + probe.status());
                return;
            }

            if (isCancelled(runtimeId, "before mark UP")) {
                cleanupContainerAndImage(containerName, imageTag);
                return;
            }
            // Mark UP in DB — persist publicBaseUrl (not internal URL) so clients can reach the container.
            try {
                lifecycleService.markUp(runtimeId, publicBaseUrl, appPort, containerName, probe.status(), LocalDateTime.now());
                log.info("[DockerRuntimeOrchestrator] Async start successful for project={}, runtimeId={}, publicUrl={}",
                        project.getId(), runtimeId, publicBaseUrl);
            } catch (Exception dbEx) {
                log.error("[DockerRuntimeOrchestrator] CRITICAL: DB update to UP failed for runtimeId={}, containerName={}, imageTag={}: {}",
                        runtimeId, containerName, imageTag, dbEx.getMessage(), dbEx);
                // Perform emergency cleanup
                cleanupContainerAndImage(containerName, imageTag);
                try {
                    lifecycleService.markStartFailed(runtimeId, "DB update failed after container start; container cleanup attempted. Error: " + dbEx.getMessage());
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            log.error("[DockerRuntimeOrchestrator] Unexpected error in async start for project={}, runtimeId={}: {}", project.getId(), runtimeId, e.getMessage(), e);
            cleanupContainerAndImage(containerName, imageTag);
            if (!isCancelled(runtimeId, "after unexpected error")) {
                lifecycleService.markBuildFailed(runtimeId, "Unexpected startup error: " + e.getMessage());
            }
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

    String generateDockerfile(RuntimeDetectionResult detection, int appPort, boolean hasMvnw, boolean hasGradlew) {
        RuntimeType type = detection.getRuntimeType() != null ? detection.getRuntimeType() : RuntimeType.UNKNOWN;
        return switch (type) {
            case SPRING_BOOT_MAVEN -> {
                if (hasMvnw) {
                    yield """
                            FROM eclipse-temurin:21-jdk AS build
                            WORKDIR /app
                            COPY mvnw .
                            COPY .mvn .mvn
                            COPY pom.xml .
                            COPY src src
                            RUN chmod +x mvnw || true
                            RUN ./mvnw -q -DskipTests clean package
                            
                            FROM eclipse-temurin:21-jre
                            WORKDIR /app
                            COPY --from=build /app/target/*.jar app.jar
                            ENV SERVER_PORT=%d
                            EXPOSE %d
                            ENTRYPOINT ["java", "-jar", "app.jar"]
                            """.formatted(appPort, appPort);
                } else {
                    yield """
                            FROM maven:3.9-eclipse-temurin-21 AS build
                            WORKDIR /app
                            COPY pom.xml .
                            COPY src src
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
            case SPRING_BOOT_GRADLE -> {
                if (hasGradlew) {
                    yield """
                            FROM eclipse-temurin:21-jdk AS build
                            WORKDIR /app
                            COPY gradlew .
                            COPY gradle gradle
                            COPY build.gradle .
                            COPY settings.gradle .
                            COPY src src
                            RUN chmod +x gradlew || true
                            RUN ./gradlew -q -x test bootJar
                            
                            FROM eclipse-temurin:21-jre
                            WORKDIR /app
                            COPY --from=build /app/build/libs/*.jar app.jar
                            ENV SERVER_PORT=%d
                            EXPOSE %d
                            ENTRYPOINT ["java", "-jar", "app.jar"]
                            """.formatted(appPort, appPort);
                } else {
                    yield """
                            FROM gradle:8-jdk21 AS build
                            WORKDIR /app
                            COPY build.gradle .
                            COPY settings.gradle .
                            COPY src src
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
            default -> {
                // If type is UNKNOWN but has wrapper/build files, we try Spring Boot Maven fallback
                boolean hasPom = Files.isRegularFile(Path.of("pom.xml")); // context-root checks will run at materialization, this is fallback
                if (hasMvnw || hasPom) {
                    yield """
                            FROM maven:3.9-eclipse-temurin-21 AS build
                            WORKDIR /app
                            COPY . .
                            RUN mvn -q -DskipTests package
                            
                            FROM eclipse-temurin:21-jre
                            WORKDIR /app
                            COPY --from=build /app/target/*.jar app.jar
                            ENV SERVER_PORT=%d
                            EXPOSE %d
                            ENTRYPOINT ["java", "-jar", "app.jar"]
                            """.formatted(appPort, appPort);
                }
                throw new IllegalArgumentException("Unsupported runtime type for Docker build: " + type);
            }
        };
    }

    /** Legacy 2-arg overload used by existing tests; detects wrapper from filesystem. */
    String generateDockerfile(RuntimeDetectionResult detection, int appPort) {
        return generateDockerfile(detection, appPort, false, false);
    }

    /** Result of Dockerfile selection: which file to use and which source it is. */
    record DockerBuildPlan(Path dockerfilePath, DockerfileSource source) {}

    static String tail(String s, int max) {
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

    CommandResult runDockerContainer(UUID runtimeId, UUID projectId, String imageTag, String containerName, int hostPort, int appPort, String network) {
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
                "-l", "ai-toolcheck.runtime-id=" + runtimeId,
                "-l", "ai-toolcheck.project-id=" + projectId,
                "-l", "ai-toolcheck.managed=true",
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
        if (raw.length() > CONTAINER_NAME_MAX) {
            raw = raw.substring(0, CONTAINER_NAME_MAX);
        }
        return raw;
    }

    String sanitizeDockerRef(String val) {
        if (val == null || val.isBlank()) {
            return "default";
        }
        String clean = val.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (clean.isBlank()) {
            return "default";
        }
        if (clean.length() > 127) {
            clean = clean.substring(0, 127);
        }
        if (!SAFE_DOCKER_REF.matcher(clean).matches()) {
            return "default";
        }
        return clean;
    }

    String buildPublicUrl(int port, String contextPath) {
        String base = properties.getPublicHost();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = (contextPath == null || contextPath.isBlank() || contextPath.equals("/"))
                ? ""
                : (contextPath.startsWith("/") ? contextPath : "/" + contextPath);
        return base + ":" + port + suffix;
    }

    /**
     * Builds the internal Docker network URL used exclusively for startup health probing.
     *
     * <p>When the backend and a runtime container share the same Docker network, Docker's
     * embedded DNS resolves {@code containerName} directly, making this URL reachable without
     * going through host NAT or public IPs. This avoids false DOWN:timeout failures caused by
     * EC2 security groups or routing policies blocking inbound traffic on host-mapped ports.
     *
     * <p><b>This URL is NEVER persisted or returned to clients.</b> The public URL
     * ({@link #buildPublicUrl}) is used for that purpose after health passes.
     *
     * @param containerName the Docker container name used as the DNS hostname within the network.
     * @param containerPort the port the application listens on inside the container.
     * @return internal base URL, e.g. {@code http://aitc-runtime-06264fb4-1780892493177:8080}
     */
    String buildInternalHealthUrl(String containerName, int containerPort) {
        return "http://" + containerName + ":" + containerPort;
    }

    /**
     * Polls health probe candidates against the given {@code baseUrl} until a 2xx response is
     * received or the timeout expires.
     *
     * <p>For Docker startup probing, {@code baseUrl} must be the <em>internal</em> container URL
     * (see {@link #buildInternalHealthUrl}), not the public URL. For external runtime checks the
     * caller passes the public/external base URL directly.
     */
    ProbeResult pollUntilHealthy(UUID runtimeId, UUID projectId, String baseUrl, int timeoutSeconds) {
        long limit = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        log.info("[DockerRuntimeOrchestrator] Polling baseUrl={} until healthy (timeout={}s)", baseUrl, timeoutSeconds);

        while (System.currentTimeMillis() < limit) {
            if (isCancelled(runtimeId, "during health polling")) {
                return new ProbeResult(false, null, "DOWN:cancelled");
            }
            for (ProbeCandidate candidate : buildProbeCandidates(runtimeId, projectId)) {
                String fullUrl = baseUrl;
                if (fullUrl.endsWith("/")) {
                    fullUrl = fullUrl.substring(0, fullUrl.length() - 1);
                }
                String target = fullUrl + candidate.path();
                int status = healthProbe.get(target, 2);
                if (status >= 200 && status < 300) {
                    String prefix = candidate.openapi() ? "UP:OPENAPI_PROBE:" : "UP:";
                    return new ProbeResult(true, candidate.path(), prefix + candidate.path() + ":" + status);
                }
            }

            sleep(3);
        }
        return new ProbeResult(false, null, "DOWN:timeout");
    }

    private List<ProbeCandidate> buildProbeCandidates(UUID runtimeId, UUID projectId) {
        List<ProbeCandidate> candidates = new ArrayList<>();
        var runtime = lifecycleService.findFresh(runtimeId);
        if (runtime != null) {
            runtime.map(SourceRuntime::getHealthCheckPath)
                    .filter(this::isSafeProbePath)
                    .ifPresent(path -> candidates.add(new ProbeCandidate(path, false)));
        }
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

    private boolean isCancelled(UUID runtimeId, String point) {
        if (lifecycleService.isStartCancelled(runtimeId)) {
            log.info("[DockerRuntimeOrchestrator] Runtime start cancelled runtimeId={} point={}", runtimeId, point);
            return true;
        }
        return false;
    }

    void cleanupContainerAndImage(String containerName, String imageTag) {
        if (hasText(containerName)) {
            commandExecutor.run(30, List.of("docker", "rm", "-f", containerName));
        }
        if (hasText(imageTag)) {
            commandExecutor.run(30, List.of("docker", "rmi", "-f", imageTag));
        }
    }

    void cleanupMaterialized(MaterializedRuntimeSource materialized) {
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
                pb.redirectErrorStream(false);
                process = pb.start();

                final Process finalProcess = process;
                Thread stdoutDrainer = drainStream(finalProcess.getInputStream(), stdoutBuf, TAIL_CHARS);
                Thread stderrDrainer = drainStream(finalProcess.getErrorStream(), stderrBuf, TAIL_CHARS);
                stdoutDrainer.start();
                stderrDrainer.start();

                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                long elapsedMs = System.currentTimeMillis() - startMs;

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
                        if (buf.length() > maxChars * 2) {
                            buf.delete(0, buf.length() - maxChars);
                        }
                    }
                } catch (IOException ignored) {
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
