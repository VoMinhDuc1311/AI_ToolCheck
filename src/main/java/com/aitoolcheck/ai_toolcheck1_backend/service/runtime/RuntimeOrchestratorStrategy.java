package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;

/**
 * Strategy interface for managing the lifecycle of an auto-started source runtime.
 *
 * <h3>Design intent:</h3>
 * <p>Different execution environments require different implementations:
 * <ul>
 *   <li>{@link DockerRuntimeOrchestrator} — builds a Docker image from source
 *       and manages container lifecycle via Docker socket (requires DinD or socket mount).</li>
 *   <li>{@link UnsupportedRuntimeOrchestrator} — used when no build capability is
 *       available; always reports {@code ENVIRONMENT_UNSUPPORTED} with a clear reason.</li>
 * </ul>
 *
 * <p>The {@link RuntimeOrchestratorFactory} selects the appropriate strategy based on
 * the {@link EnvironmentCapabilityReport} produced at startup.
 *
 * <h3>Contract:</h3>
 * <ul>
 *   <li>{@link #start} MUST NOT return a runtime with status {@code UP} unless the
 *       process/container is genuinely reachable.</li>
 *   <li>{@link #stop} is idempotent — calling on an already-stopped runtime must not throw.</li>
 * </ul>
 */
public interface RuntimeOrchestratorStrategy {

    /**
     * Builds and starts a runtime for the given project.
     *
     * <p>Implementations are responsible for:
     * <ol>
     *   <li>Persisting a {@link SourceRuntime} record with appropriate status transitions.</li>
     *   <li>Setting {@link SourceRuntime#setPublicBaseUrl(String)} only when the
     *       process is confirmed reachable.</li>
     *   <li>Setting {@link SourceRuntime#setLastError(String)} with a clear diagnostic
     *       when start fails.</li>
     * </ol>
     *
     * @param project   the project whose source should be built and run.
     * @param detection the build tool detection result for this project's source.
     * @return the persisted {@link SourceRuntime} reflecting the final state.
     */
    SourceRuntime start(SourceProject project, RuntimeDetectionResult detection);

    /**
     * Stops the given runtime.
     *
     * <p>For {@link UnsupportedRuntimeOrchestrator} this is a no-op that marks
     * the record STOPPED. For Docker-based orchestrators this terminates the container.
     *
     * @param runtime the runtime record to stop.
     * @return the updated, persisted {@link SourceRuntime}.
     */
    SourceRuntime stop(SourceRuntime runtime);

    /**
     * Returns a human-readable name identifying this strategy, used for logging.
     */
    String name();
}
