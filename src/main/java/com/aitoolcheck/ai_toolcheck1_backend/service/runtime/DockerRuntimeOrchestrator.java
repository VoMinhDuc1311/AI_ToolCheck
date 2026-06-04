package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
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
        SourceRuntime runtime = sourceRuntimeRepository.save(buildRuntime(project, detection, RuntimeStatus.BUILDING, null));
        runtime.setBuildStartedAt(LocalDateTime.now());
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
            Path dockerfilePath = resolveDockerfile(materialized.getRootDir(), detection, appPort);
            imageTag = sanitizeDockerRef(properties.getContainerPrefix()) + ":" + sanitizeDockerRef(project.getId() + "-" + System.currentTimeMillis());
            containerName = sanitizeContainerName(properties.getContainerPrefix(), project.getId(), System.currentTimeMillis());
            int hostPort = allocatePort(project.getId());

            CommandResult build = runDockerBuild(materialized.getRootDir(), imageTag, dockerfilePath);
            if (!build.success()) {
                cleanupContainerAndImage(containerName, imageTag);
                return failRuntime(runtime, RuntimeStatus.BUILD_FAILED, "docker build failed: " + build.summary());
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

    Path resolveDockerfile(Path root, RuntimeDetectionResult detection, int appPort) throws IOException {
        Path existing = root.resolve("Dockerfile");
        if (Files.isRegularFile(existing)) {
            return existing;
        }
        Path generated = root.resolve("Dockerfile.autorun");
        Files.writeString(generated, generateDockerfile(detection, appPort));
        return generated;
    }

    String generateDockerfile(RuntimeDetectionResult detection, int appPort) {
        RuntimeType type = detection.getRuntimeType();
        if (type == RuntimeType.SPRING_BOOT_MAVEN) {
            return """
                    FROM eclipse-temurin:21-jdk AS build
                    WORKDIR /app
                    COPY . .
                    RUN chmod +x mvnw || true
                    RUN if [ -x ./mvnw ]; then ./mvnw -B -DskipTests clean package; else mvn -B -DskipTests clean package; fi

                    FROM eclipse-temurin:21-jre
                    WORKDIR /app
                    COPY --from=build /app/target/*.jar app.jar
                    ENV SERVER_PORT=%d
                    EXPOSE %d
                    ENTRYPOINT ["java", "-jar", "app.jar"]
                    """.formatted(appPort, appPort);
        }
        if (type == RuntimeType.SPRING_BOOT_GRADLE) {
            return """
                    FROM eclipse-temurin:21-jdk AS build
                    WORKDIR /app
                    COPY . .
                    RUN chmod +x gradlew || true
                    RUN if [ -x ./gradlew ]; then ./gradlew -x test bootJar; else gradle -x test bootJar; fi

                    FROM eclipse-temurin:21-jre
                    WORKDIR /app
                    COPY --from=build /app/build/libs/*.jar app.jar
                    ENV SERVER_PORT=%d
                    EXPOSE %d
                    ENTRYPOINT ["java", "-jar", "app.jar"]
                    """.formatted(appPort, appPort);
        }
        throw new IllegalArgumentException("Unsupported runtime type for Docker build: " + type);
    }

    CommandResult runDockerBuild(Path contextDir, String imageTag, Path dockerfilePath) {
        return commandExecutor.run(properties.getBuildTimeoutSeconds(), List.of(
                "docker", "build",
                "-f", dockerfilePath.toAbsolutePath().toString(),
                "-t", imageTag,
                contextDir.toAbsolutePath().toString()
        ));
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

    record CommandResult(boolean success, int exitCode, String output) {
        String summary() {
            if (output == null || output.isBlank()) {
                return "exitCode=" + exitCode;
            }
            String masked = output.replaceAll("(?i)(token|password|secret|api[_-]?key)=\\S+", "$1=***");
            return masked.length() > 500 ? masked.substring(0, 500) : masked;
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
        @Override
        public CommandResult run(int timeoutSeconds, List<String> command) {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                String output = new String(process.getInputStream().readAllBytes());
                if (!finished) {
                    process.destroyForcibly();
                    return new CommandResult(false, -1, "timed out after " + timeoutSeconds + "s");
                }
                return new CommandResult(process.exitValue() == 0, process.exitValue(), output);
            } catch (IOException e) {
                return new CommandResult(false, -1, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new CommandResult(false, -1, "interrupted");
            }
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
