package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req.RegisterExternalRuntimeRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;

import java.util.List;
import java.util.UUID;

public interface SourceRuntimeService {

    /**
     * Returns the most recent runtime record for the project,
     * or a synthetic NOT_CREATED response if none exists.
     */
    SourceRuntimeResponse getCurrentRuntime(UUID projectId);

    /**
     * Returns all runtime records for the project, newest first.
     */
    List<SourceRuntimeResponse> listRuntimes(UUID projectId);

    /**
     * Registers an EXTERNAL runtime with an explicit base URL.
     * The runtime is immediately marked UP without further health-checking,
     * because the caller provides a URL they assert is already live.
     *
     * <p>Any previously UP EXTERNAL runtime for the same project is stopped
     * before the new one is registered.
     */
    RuntimeActionResponse registerExternalRuntime(UUID projectId, RegisterExternalRuntimeRequest request);

    /**
     * Probes the runtime's public base URL with a lightweight HTTP GET.
     * Updates {@code lastHealthStatus} and returns an action response.
     * Does NOT change {@code runtimeStatus} — only records the probe result.
     */
    RuntimeActionResponse healthCheckRuntime(UUID projectId);

    /**
     * Ensures there is a RUNNING runtime ready for test execution.
     *
     * <ul>
     *   <li>EXTERNAL mode — runtime must be registered and UP.</li>
     *   <li>AUTO mode — attempts to detect/materialize source; Phase 2 will
     *       actually build/run Docker containers. In Phase 1 this always throws
     *       a descriptive {@link com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException}.</li>
     * </ul>
     */
    SourceRuntimeResponse ensureRuntimeReady(UUID projectId);

    /**
     * For AUTO_RUNTIME_FROM_SOURCE: triggers detection + materialization skeleton.
     * Docker build/run is Phase 2. Returns action response with honest status.
     */
    RuntimeActionResponse startRuntime(UUID projectId);

    /**
     * For AUTO_RUNTIME_FROM_SOURCE: triggers rebuild.
     * Docker build/run is Phase 2.
     */
    RuntimeActionResponse rebuildRuntime(UUID projectId);

    /**
     * Stops the current runtime for the project.
     *
     * <ul>
     *   <li>EXTERNAL runtime: marks as STOPPED in DB. Does NOT call any remote endpoint.</li>
     *   <li>AUTO runtime (Phase 1): marks as STOPPED. Actual container teardown is Phase 2.</li>
     *   <li>No runtime: safe no-op.</li>
     * </ul>
     */
    RuntimeActionResponse stopRuntime(UUID projectId);

    /**
     * Resolves the effective base URL for a TestRun, in priority order:
     * <ol>
     *   <li>{@code requestBaseUrl} if non-blank (validated).</li>
     *   <li>The {@code publicBaseUrl} of the project's UP SourceRuntime.</li>
     *   <li>{@code projectDefaultTargetBaseUrl} if non-blank (validated).</li>
     *   <li>Throws {@link com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException}
     *       with a clear guidance message.</li>
     * </ol>
     */
    String resolveBaseUrlForTestRun(UUID projectId, String requestBaseUrl, String projectDefaultTargetBaseUrl);

    /**
     * Returns the latest environment capability report for this host.
     * Probes Docker CLI, Docker socket, JDK, Maven, Gradle, and writable temp dir.
     * Results indicate whether autostart can proceed and what is missing.
     */
    EnvironmentCapabilityReport getEnvironmentCapabilities();

    /**
     * Updates the custom health-check path used by {@link #healthCheckRuntime(UUID)}.
     * By default, the probe tries /actuator/health, /health, /healthz, /.
     * This method allows specifying a project-specific path (e.g. /greeting, /api/ping).
     *
     * @param projectId       the project whose runtime health-check path should be updated.
     * @param healthCheckPath the custom path to probe, e.g. {@code /greeting}.
     * @return updated action response.
     */
    RuntimeActionResponse updateHealthCheckPath(UUID projectId, String healthCheckPath);
}

