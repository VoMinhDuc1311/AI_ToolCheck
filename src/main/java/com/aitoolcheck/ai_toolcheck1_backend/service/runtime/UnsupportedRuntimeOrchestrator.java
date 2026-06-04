package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * {@link RuntimeOrchestratorStrategy} used when the host environment does NOT
 * have the required capabilities to build or run source automatically.
 *
 * <p><strong>This is the current active strategy on EC2.</strong>
 *
 * <h3>Why this is honest:</h3>
 * <p>The backend runs on {@code eclipse-temurin:21-jre} — only a JRE, no JDK,
 * no Maven, no Gradle, and no Docker socket mounted. The {@code docker-compose.prod.yml}
 * does not mount {@code /var/run/docker.sock}, so Docker-in-Docker is impossible.
 *
 * <p>Rather than pretending an autostart succeeded, this strategy immediately
 * sets {@code runtimeStatus = ENVIRONMENT_UNSUPPORTED} with a clear message
 * explaining what infrastructure change is needed.
 *
 * <h3>Future replacement:</h3>
 * <p>When EC2 is upgraded (e.g. DinD sidecar mounted, JDK image used, or a
 * dedicated build agent is available), the {@link RuntimeOrchestratorFactory}
 * will select {@link DockerRuntimeOrchestrator} instead and this class will
 * only be used as the final fallback.
 */
@RequiredArgsConstructor
@Slf4j
public class UnsupportedRuntimeOrchestrator implements RuntimeOrchestratorStrategy {

    private static final String REASON =
            "Auto-runtime is not available in this environment. " +
            "The backend container does not have a Docker socket mounted and does not include " +
            "a JDK or build tools (Maven/Gradle). " +
            "To enable auto-runtime, either: " +
            "(1) mount /var/run/docker.sock into the backend container, " +
            "(2) switch to eclipse-temurin:21-jdk and install Maven/Gradle, or " +
            "(3) use a dedicated build-agent sidecar. " +
            "In the meantime, use External Base URL to register an already-running application.";

    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final String capabilitySummary;

    @Override
    public SourceRuntime start(SourceProject project, RuntimeDetectionResult detection) {
        log.warn("[UnsupportedRuntimeOrchestrator] Cannot start runtime for project={} — environment unsupported. {}",
                project.getId(), capabilitySummary);

        SourceRuntime runtime = buildUnsupportedRecord(project, detection);
        runtime.setBuildStartedAt(LocalDateTime.now());
        runtime.setBuildFinishedAt(LocalDateTime.now());
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    public SourceRuntime stop(SourceRuntime runtime) {
        // Idempotent — if already stopped or unsupported, just mark stopped
        if (runtime.getRuntimeStatus() != RuntimeStatus.STOPPED) {
            runtime.setRuntimeStatus(RuntimeStatus.STOPPED);
            runtime.setStoppedAt(LocalDateTime.now());
            sourceRuntimeRepository.save(runtime);
        }
        return runtime;
    }

    @Override
    public String name() {
        return "UnsupportedRuntimeOrchestrator";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private SourceRuntime buildUnsupportedRecord(SourceProject project, RuntimeDetectionResult detection) {
        return SourceRuntime.builder()
                .sourceProject(project)
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(RuntimeStatus.ENVIRONMENT_UNSUPPORTED)
                .runtimeType(detection == null || detection.getRuntimeType() == null
                        ? RuntimeType.UNKNOWN
                        : detection.getRuntimeType())
                .detectedPort(detection == null ? null : detection.getDetectedPort())
                .contextPath(detection == null ? null : detection.getContextPath())
                .publicBaseUrl(null)
                .internalBaseUrl(null)
                .lastHealthStatus(null)
                .lastError(capabilitySummary != null ? capabilitySummary : REASON)
                .build();
    }
}
