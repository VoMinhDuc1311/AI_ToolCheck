package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link RuntimeOrchestratorStrategy} that builds a Docker image from source and
 * manages container lifecycle via the Docker CLI and Docker socket.
 *
 * <h3>Prerequisites (all must be satisfied):</h3>
 * <ul>
 *   <li>{@code /var/run/docker.sock} must be mounted into the backend container.</li>
 *   <li>{@code docker} CLI must be present on PATH inside the backend container.</li>
 *   <li>A writable temp directory must be available for source materialization.</li>
 * </ul>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>Materialize source files to a temp directory via
 *       {@link RuntimeSourceMaterializer#materialize(java.util.UUID)}.</li>
 *   <li>Detect which network to join and which port to allocate (from config range).</li>
 *   <li>Generate a {@code Dockerfile} targeting the detected runtime type
 *       (Maven/Gradle Spring Boot).</li>
 *   <li>Run {@code docker build} — update runtime to {@code BUILDING}.</li>
 *   <li>Run {@code docker run} with the allocated host port — update to {@code STARTING}.</li>
 *   <li>Poll health endpoint until UP or timeout — update to {@code UP} or {@code BUILD_FAILED}.</li>
 * </ol>
 *
 * <p><strong>Phase 2 note:</strong> This class is fully wired and will be selected by
 * {@link RuntimeOrchestratorFactory} when Docker is available. The build/run loop
 * implementation is a thin skeleton in this commit — it transitions the runtime to
 * {@code BUILD_FAILED} with an actionable reason rather than fake {@code UP}. The
 * full container orchestration implementation is deferred to Phase 3 (when EC2 gets
 * Docker socket mount or DinD sidecar).
 */
@RequiredArgsConstructor
@Slf4j
public class DockerRuntimeOrchestrator implements RuntimeOrchestratorStrategy {

    private static final List<String> HEALTH_PROBE_PATHS = List.of(
            "/actuator/health", "/health", "/healthz", "/"
    );
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int HEALTH_POLL_INTERVAL_SECONDS = 3;

    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final RuntimeSourceMaterializer runtimeSourceMaterializer;
    private final RuntimeAutoProperties properties;

    @Override
    public SourceRuntime start(SourceProject project, RuntimeDetectionResult detection) {
        log.info("[DockerRuntimeOrchestrator] Starting runtime for project={} type={}",
                project.getId(), detection.getRuntimeType());

        // Step 1: Persist BUILDING status
        SourceRuntime runtime = sourceRuntimeRepository.save(buildRuntime(project, detection, RuntimeStatus.BUILDING, null));
        runtime.setBuildStartedAt(LocalDateTime.now());
        sourceRuntimeRepository.save(runtime);

        // Step 2: Materialize source
        MaterializedRuntimeSource materialized;
        try {
            materialized = runtimeSourceMaterializer.materialize(project.getId());
        } catch (Exception e) {
            log.error("[DockerRuntimeOrchestrator] Materialization failed for project={}: {}",
                    project.getId(), e.getMessage());
            return failRuntime(runtime, "Source materialization failed: " + e.getMessage());
        }

        try {
            // Step 3: Generate Dockerfile
            String dockerfileContent = generateDockerfile(detection);
            java.nio.file.Path dockerfilePath = materialized.getRootDir().resolve("Dockerfile.autorun");
            try {
                java.nio.file.Files.writeString(dockerfilePath, dockerfileContent);
            } catch (IOException e) {
                return failRuntime(runtime, "Could not write Dockerfile: " + e.getMessage());
            }

            // Step 4: docker build
            String imageTag = "ai-toolcheck-runtime-" + project.getId().toString().substring(0, 8);
            boolean built = runDockerBuild(materialized.getRootDir(), imageTag, dockerfilePath);
            if (!built) {
                return failRuntime(runtime, "docker build failed. Check that the source compiles correctly.");
            }

            runtime.setBuildFinishedAt(LocalDateTime.now());
            runtime.setImageName(imageTag);
            runtime.setRuntimeStatus(RuntimeStatus.STARTING);
            sourceRuntimeRepository.save(runtime);

            // Step 5: docker run
            int hostPort = allocatePort(project.getId());
            String containerName = "ait-runtime-" + project.getId().toString().substring(0, 8);
            boolean started = runDockerContainer(imageTag, containerName, hostPort, properties.getDockerNetwork());
            if (!started) {
                stopContainerQuietly(containerName);
                return failRuntime(runtime, "docker run failed. Container may have crashed on startup.");
            }

            runtime.setContainerName(containerName);
            runtime.setInternalPort(hostPort);
            int contextPort = detection.getDetectedPort() != null ? detection.getDetectedPort() : properties.getInternalPort();
            String contextPath = detection.getContextPath() != null ? detection.getContextPath() : "";
            String publicUrl = "http://" + resolveHostForRuntime() + ":" + hostPort + contextPath;
            runtime.setPublicBaseUrl(publicUrl);
            runtime.setDockerNetwork(properties.getDockerNetwork());

            // Step 6: Health poll
            String healthPath = pollUntilHealthy(publicUrl, properties.getStartupTimeoutSeconds());
            if (healthPath == null) {
                stopContainerQuietly(containerName);
                return failRuntime(runtime, "Container started but did not respond on any health endpoint within "
                        + properties.getStartupTimeoutSeconds() + "s. URL: " + publicUrl);
            }

            runtime.setRuntimeStatus(RuntimeStatus.UP);
            runtime.setStartedAt(LocalDateTime.now());
            runtime.setHealthCheckPath(healthPath);
            runtime.setLastHealthStatus("UP:" + healthPath);
            runtime.setLastError(null);
            log.info("[DockerRuntimeOrchestrator] Runtime UP for project={} url={}", project.getId(), publicUrl);
            return sourceRuntimeRepository.save(runtime);

        } finally {
            // Always clean up the temp dir — image is already in Docker if build succeeded
            try {
                if (materialized.getRootDir() != null) {
                    deleteDirectory(materialized.getRootDir());
                }
            } catch (Exception e) {
                log.warn("[DockerRuntimeOrchestrator] Failed to clean up temp dir: {}", e.getMessage());
            }
        }
    }

    @Override
    public SourceRuntime stop(SourceRuntime runtime) {
        String containerName = runtime.getContainerName();
        if (containerName != null && !containerName.isBlank()) {
            stopContainerQuietly(containerName);
        }
        runtime.setRuntimeStatus(RuntimeStatus.STOPPED);
        runtime.setStoppedAt(LocalDateTime.now());
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    public String name() {
        return "DockerRuntimeOrchestrator";
    }

    // ── Dockerfile generation ─────────────────────────────────────────────────

    private String generateDockerfile(RuntimeDetectionResult detection) {
        RuntimeType type = detection.getRuntimeType();
        int appPort = detection.getDetectedPort() != null ? detection.getDetectedPort() : 8080;
        String contextPath = detection.getContextPath() != null ? detection.getContextPath() : "";

        if (type == RuntimeType.SPRING_BOOT_MAVEN) {
            return """
                    FROM eclipse-temurin:21-jdk AS build
                    WORKDIR /app
                    COPY . .
                    RUN chmod +x mvnw 2>/dev/null || true
                    RUN ./mvnw -B -DskipTests clean package 2>/dev/null || mvn -B -DskipTests clean package
                    
                    FROM eclipse-temurin:21-jre
                    WORKDIR /app
                    COPY --from=build /app/target/*.jar app.jar
                    ENV SERVER_PORT=%d
                    EXPOSE %d
                    ENTRYPOINT ["java", "-jar", "app.jar"]
                    """.formatted(appPort, appPort);
        } else if (type == RuntimeType.SPRING_BOOT_GRADLE) {
            return """
                    FROM eclipse-temurin:21-jdk AS build
                    WORKDIR /app
                    COPY . .
                    RUN chmod +x gradlew 2>/dev/null || true
                    RUN ./gradlew -x test bootJar 2>/dev/null || gradle -x test bootJar
                    
                    FROM eclipse-temurin:21-jre
                    WORKDIR /app
                    COPY --from=build /app/build/libs/*.jar app.jar
                    ENV SERVER_PORT=%d
                    EXPOSE %d
                    ENTRYPOINT ["java", "-jar", "app.jar"]
                    """.formatted(appPort, appPort);
        } else {
            throw new IllegalArgumentException("Unsupported runtime type for Docker build: " + type);
        }
    }

    // ── Docker process helpers ────────────────────────────────────────────────

    private boolean runDockerBuild(java.nio.file.Path contextDir, String imageTag, java.nio.file.Path dockerfilePath) {
        return runProcess(properties.getBuildTimeoutSeconds(),
                "docker", "build",
                "-f", dockerfilePath.toAbsolutePath().toString(),
                "-t", imageTag,
                contextDir.toAbsolutePath().toString());
    }

    private boolean runDockerContainer(String imageTag, String containerName, int hostPort, String network) {
        return runProcess(30,
                "docker", "run", "-d",
                "--name", containerName,
                "-p", hostPort + ":" + properties.getInternalPort(),
                "--network", network,
                "--restart", "no",
                imageTag);
    }

    private void stopContainerQuietly(String containerName) {
        try {
            runProcess(30, "docker", "rm", "-f", containerName);
        } catch (Exception ignored) { }
    }

    private boolean runProcess(int timeoutSeconds, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[DockerRuntimeOrchestrator] Command '{}' timed out after {}s", command[0], timeoutSeconds);
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("[DockerRuntimeOrchestrator] Command '{}' failed: {}", command[0], e.getMessage());
            return false;
        }
    }

    // ── Health polling ────────────────────────────────────────────────────────

    /**
     * Polls health endpoints on the given base URL until a 2xx response is received
     * or the timeout expires.
     *
     * @return the path that responded with 2xx, or {@code null} if timed out.
     */
    private String pollUntilHealthy(String publicUrl, int timeoutSeconds) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HEALTH_PROBE_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            for (String path : HEALTH_PROBE_PATHS) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(publicUrl + path))
                            .timeout(HEALTH_PROBE_TIMEOUT)
                            .GET()
                            .build();
                    HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        log.info("[DockerRuntimeOrchestrator] Health UP at {} (attempt {})", publicUrl + path, attempt);
                        return path;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception ignored) { }
            }
            try {
                TimeUnit.SECONDS.sleep(HEALTH_POLL_INTERVAL_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log.warn("[DockerRuntimeOrchestrator] Health poll timed out after {}s for {}", timeoutSeconds, publicUrl);
        return null;
    }

    // ── Port allocation ───────────────────────────────────────────────────────

    /**
     * Allocates an available host port in the configured range (default 18080–18999).
     * Tries each port in order and returns the first that is not in use by Docker.
     * Falls back to the start of the range if all are taken (should not happen in practice).
     */
    private int allocatePort(java.util.UUID projectId) {
        int start = properties.getHostPortRangeStart();
        int end = properties.getHostPortRangeEnd();

        for (int port = start; port <= end; port++) {
            if (isPortAvailable(port)) {
                log.info("[DockerRuntimeOrchestrator] Allocated host port {} for project={}", port, projectId);
                return port;
            }
        }
        log.warn("[DockerRuntimeOrchestrator] No free port found in range {}-{}, using {}", start, end, start);
        return start;
    }

    private boolean isPortAvailable(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String resolveHostForRuntime() {
        // In Docker-network mode, containers communicate via service name.
        // Public URL uses the EC2 host IP or localhost depending on context.
        // We default to 127.0.0.1 — the caller can override via publicBaseUrl update.
        return "127.0.0.1";
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private SourceRuntime failRuntime(SourceRuntime runtime, String reason) {
        log.error("[DockerRuntimeOrchestrator] Runtime failed for project={}: {}", 
                runtime.getSourceProject().getId(), reason);
        runtime.setRuntimeStatus(RuntimeStatus.BUILD_FAILED);
        runtime.setBuildFinishedAt(LocalDateTime.now());
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

    private void deleteDirectory(java.nio.file.Path dir) throws IOException {
        if (dir == null || !java.nio.file.Files.exists(dir)) return;
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }
}
