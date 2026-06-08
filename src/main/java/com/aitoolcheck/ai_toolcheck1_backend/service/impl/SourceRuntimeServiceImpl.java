package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.common.RuntimeTargetUrlValidator;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req.RegisterExternalRuntimeRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeDetectorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.runtime.RuntimeOrchestratorFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SourceRuntimeServiceImpl implements SourceRuntimeService {

    // ── Public status/error codes ────────────────────────────────────────────

    public static final String CODE_SUCCESS = "SUCCESS";
    public static final String CODE_REGISTERED = "REGISTERED";
    public static final String CODE_STOPPED = "STOPPED";
    public static final String CODE_HEALTH_UP = "HEALTH_UP";
    public static final String CODE_HEALTH_DOWN = "HEALTH_DOWN";
    public static final String CODE_HEALTH_UNKNOWN = "HEALTH_UNKNOWN";
    public static final String CODE_NO_RUNTIME = "NO_RUNTIME";

    /** @deprecated Use {@link #AUTO_RUNTIME_DISABLED_MESSAGE} — kept for test back-compat */
    public static final String AUTO_RUNTIME_DISABLED_CODE = "AUTO_RUNTIME_DISABLED";
    public static final String AUTO_RUNTIME_DISABLED_MESSAGE =
            "Auto runtime from uploaded source is disabled. Use External Base URL.";
    public static final String AUTO_RUNTIME_NOT_IMPLEMENTED_CODE = "AUTO_RUNTIME_NOT_IMPLEMENTED";
    public static final String AUTO_RUNTIME_NOT_IMPLEMENTED_MESSAGE =
            "Auto runtime from uploaded source is not enabled yet.";
    public static final String AUTO_RUNTIME_UNSUPPORTED_CODE = "AUTO_RUNTIME_UNSUPPORTED";
    public static final String AUTO_RUNTIME_PHASE_2_READY_MESSAGE =
            "Auto runtime detection/materialization succeeded, but Docker runtime build/start is not implemented yet.";
    public static final String CODE_ENVIRONMENT_UNSUPPORTED = "ENVIRONMENT_UNSUPPORTED";

    /** Timeout for health-check probe HTTP GET requests. */
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);

    private static final List<String> HEALTH_CHECK_PATHS = List.of(
            "/actuator/health", "/health", "/healthz", "/"
    );

    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final ProjectAccessService projectAccessService;
    private final RuntimeAutoProperties runtimeAutoProperties;
    private final RuntimeDetectorService runtimeDetectorService;
    private final RuntimeSourceMaterializer runtimeSourceMaterializer;
    private final RuntimeOrchestratorFactory orchestratorFactory;

    // ── Read operations ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public SourceRuntimeResponse getCurrentRuntime(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);
        return sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .map(this::toResponse)
                .orElseGet(() -> notCreatedResponse(projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceRuntimeResponse> listRuntimes(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);
        return sourceRuntimeRepository.findBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── EXTERNAL runtime registration ────────────────────────────────────────

    /**
     * Registers an external (user-managed) runtime for the project.
     *
     * <p>Resolution rules:
     * <ol>
     *   <li>Validate and normalise the provided base URL.</li>
     *   <li>Stop any existing UP EXTERNAL runtime for the same project.</li>
     *   <li>Create (or reuse) a SourceRuntime record with {@code runtimeMode=EXTERNAL_BASE_URL},
     *       {@code runtimeStatus=UP}, and the validated public URL.</li>
     * </ol>
     *
     * <p>This does NOT perform a health check — the caller asserts the URL is live.
     */
    @Override
    @Transactional
    public RuntimeActionResponse registerExternalRuntime(UUID projectId, RegisterExternalRuntimeRequest request) {
        if (request == null) {
            throw new BadRequestException("RegisterExternalRuntimeRequest is required");
        }

        SourceProject sourceProject = projectAccessService.requireCanManageProject(projectId);

        // Validate and normalise baseUrl
        String normalised = RuntimeTargetUrlValidator.normalise(request.getBaseUrl());
        if (normalised == null || normalised.isBlank()) {
            throw new BadRequestException("baseUrl is required for external runtime registration");
        }

        log.info("[SourceRuntime] Registering EXTERNAL runtime for project={} baseUrl={}", projectId, normalised);

        // Stop any existing UP EXTERNAL runtime for this project
        stopExistingUpRuntimes(projectId);

        // Create a new EXTERNAL runtime record
        SourceRuntime runtime = SourceRuntime.builder()
                .sourceProject(sourceProject)
                .runtimeMode(RuntimeMode.EXTERNAL_BASE_URL)
                .runtimeStatus(RuntimeStatus.UP)
                .runtimeType(RuntimeType.UNKNOWN)
                .publicBaseUrl(normalised)
                .containerName(request.getLabel())   // label stored in container_name for display
                .startedAt(LocalDateTime.now())
                .build();

        SourceRuntime saved = sourceRuntimeRepository.save(runtime);
        log.info("[SourceRuntime] EXTERNAL runtime registered: id={} url={}", saved.getId(), normalised);

        return RuntimeActionResponse.builder()
                .code(CODE_REGISTERED)
                .message("External runtime registered. BaseUrl: " + normalised)
                .runtime(toResponse(saved))
                .build();
    }

    // ── Health check ─────────────────────────────────────────────────────────

    /**
     * Probes the current runtime's public base URL.
     *
     * <p>Tries known health-check paths in order: {@code /actuator/health},
     * {@code /health}, {@code /healthz}, {@code /}. The runtime is considered
     * healthy if any path responds with HTTP 2xx.
     *
     * <p>This updates {@code lastHealthStatus} only — it does NOT change
     * {@code runtimeStatus} so as not to disrupt in-flight test runs.
     */
    @Override
    @Transactional
    public RuntimeActionResponse healthCheckRuntime(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);

        SourceRuntime runtime = sourceRuntimeRepository
                .findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No runtime found for project: " + projectId));

        String baseUrl = runtime.getPublicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            runtime.setLastHealthStatus("NO_URL");
            sourceRuntimeRepository.save(runtime);
            return RuntimeActionResponse.builder()
                    .code(CODE_HEALTH_UNKNOWN)
                    .message("Runtime has no public base URL configured.")
                    .runtime(toResponse(runtime))
                    .build();
        }

        // Build probe list: custom path first (if configured), then defaults
        List<String> probePaths = new ArrayList<>();
        String customPath = runtime.getHealthCheckPath();
        if (customPath != null && !customPath.isBlank()) {
            probePaths.add(customPath);
        }
        for (String p : HEALTH_CHECK_PATHS) {
            if (!probePaths.contains(p)) {
                probePaths.add(p);
            }
        }

        String healthResult = probeHealth(baseUrl, probePaths);
        runtime.setLastHealthStatus(healthResult);
        sourceRuntimeRepository.save(runtime);

        boolean up = healthResult.startsWith("UP:");
        log.info("[SourceRuntime] Health check projectId={} result={}", projectId, healthResult);

        return RuntimeActionResponse.builder()
                .code(up ? CODE_HEALTH_UP : CODE_HEALTH_DOWN)
                .message(up ? "Runtime is healthy: " + healthResult : "Runtime health check failed: " + healthResult)
                .runtime(toResponse(runtime))
                .build();
    }

    // ── ensureRuntimeReady (used by TestRun creation) ────────────────────────

    /**
     * Returns the current runtime if it is UP (either EXTERNAL or AUTO).
     * For AUTO mode in Phase 1, always throws — Docker build is not implemented.
     * For EXTERNAL mode, returns the runtime response if UP.
     *
     * @throws BadRequestException if no UP runtime exists.
     */
    @Override
    @Transactional(readOnly = true)
    public SourceRuntimeResponse ensureRuntimeReady(UUID projectId) {
        projectAccessService.requireCanCreateTestRun(projectId);

        // Check if there is an UP runtime
        var upRuntime = sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP);

        if (upRuntime.isPresent()) {
            SourceRuntime rt = upRuntime.get();
            log.info("[SourceRuntime] Found UP runtime id={} url={} for project={}",
                    rt.getId(), rt.getPublicBaseUrl(), projectId);
            return toResponse(rt);
        }

        if (!runtimeAutoProperties.isEnabled()) {
            throw new BadRequestException(AUTO_RUNTIME_DISABLED_MESSAGE);
        }

        // No UP runtime and auto is enabled — capability check
        EnvironmentCapabilityReport cap = orchestratorFactory.getLastReport();
        if (cap != null && !cap.canAutoStart()) {
            throw new BadRequestException(
                    "No UP runtime found and environment cannot auto-start. " +
                    "Register an External Runtime or check GET /runtime/environment for details.");
        }
        throw new BadRequestException(
                "No UP runtime found. Register an External Runtime via POST /runtime/external or start an auto-runtime.");
    }

    // ── Start / Rebuild (AUTO runtime skeleton) ───────────────────────────────

    @Override
    @Transactional
    public RuntimeActionResponse startRuntime(UUID projectId) {
        return startRuntime(projectId, BuildStrategy.AUTO);
    }

    @Override
    @Transactional
    public RuntimeActionResponse startRuntime(UUID projectId, BuildStrategy strategy) {
        SourceProject sourceProject = projectAccessService.requireCanManageProject(projectId);
        return runOrchestratorAction(sourceProject, strategy != null ? strategy : BuildStrategy.AUTO);
    }

    @Override
    @Transactional
    public RuntimeActionResponse rebuildRuntime(UUID projectId) {
        stopRuntime(projectId);
        SourceProject sourceProject = projectAccessService.requireCanManageProject(projectId);
        return runOrchestratorAction(sourceProject, BuildStrategy.AUTO);
    }

    // ── Stop ─────────────────────────────────────────────────────────────────

    /**
     * Stops the most recent runtime for the project.
     *
     * <ul>
     *   <li>If no runtime exists → safe no-op, returns STOPPED response.</li>
     *   <li>If runtime is already STOPPED → idempotent, returns STOPPED.</li>
     *   <li>Otherwise → marks as STOPPED, sets stoppedAt, persists.</li>
     * </ul>
     *
     * This is a best-effort operation. For EXTERNAL runtimes, the actual
     * application process is NOT terminated (it's user-managed).
     * For AUTO runtimes (Phase 2), Docker container teardown will happen here.
     */
    @Override
    @Transactional
    public RuntimeActionResponse stopRuntime(UUID projectId) {
        projectAccessService.requireCanManageProject(projectId);

        var runtimeOpt = sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId);

        if (runtimeOpt.isEmpty()) {
            return RuntimeActionResponse.builder()
                    .code(CODE_STOPPED)
                    .message("No runtime was running for this project.")
                    .runtime(stoppedNotCreatedResponse(projectId))
                    .build();
        }

        SourceRuntime runtime = runtimeOpt.get();

        if (runtime.getRuntimeStatus() == RuntimeStatus.STOPPED) {
            return RuntimeActionResponse.builder()
                    .code(CODE_STOPPED)
                    .message("Runtime was already stopped.")
                    .runtime(toResponse(runtime))
                    .build();
        }

        // For AUTO_RUNTIME containers: delegate stop to orchestrator (handles docker rm)
        if (runtime.getRuntimeMode() == RuntimeMode.AUTO_RUNTIME_FROM_SOURCE
                && runtime.getContainerName() != null) {
            SourceRuntime stopped = orchestratorFactory.getStrategy().stop(runtime);
            log.info("[SourceRuntime] AUTO runtime stopped via orchestrator for project={} id={}", projectId, runtime.getId());
            return RuntimeActionResponse.builder()
                    .code(CODE_STOPPED)
                    .message("Auto runtime stopped successfully.")
                    .runtime(toResponse(stopped))
                    .build();
        }

        // EXTERNAL or no container name — just mark STOPPED
        runtime.setRuntimeStatus(RuntimeStatus.STOPPED);
        runtime.setStoppedAt(LocalDateTime.now());
        sourceRuntimeRepository.save(runtime);
        log.info("[SourceRuntime] Runtime stopped for project={} id={}", projectId, runtime.getId());

        return RuntimeActionResponse.builder()
                .code(CODE_STOPPED)
                .message("Runtime stopped successfully.")
                .runtime(toResponse(runtime))
                .build();
    }

    // ── Phase 2: Environment capabilities and health-check path ───────────────

    @Override
    public EnvironmentCapabilityReport getEnvironmentCapabilities() {
        EnvironmentCapabilityReport cached = orchestratorFactory.getLastReport();
        if (cached != null) {
            return cached;
        }
        return orchestratorFactory.reprobeAndSelect();
    }

    @Override
    @Transactional
    public RuntimeActionResponse updateHealthCheckPath(UUID projectId, String healthCheckPath) {
        projectAccessService.requireCanManageProject(projectId);

        if (healthCheckPath == null || healthCheckPath.isBlank()) {
            throw new BadRequestException("healthCheckPath must not be blank. Example: /greeting");
        }
        if (!healthCheckPath.startsWith("/")) {
            throw new BadRequestException("healthCheckPath must start with /. Example: /greeting");
        }
        if (healthCheckPath.length() > 500) {
            throw new BadRequestException("healthCheckPath must not exceed 500 characters");
        }

        SourceRuntime runtime = sourceRuntimeRepository
                .findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("No runtime found for project: " + projectId));

        String oldPath = runtime.getHealthCheckPath();
        runtime.setHealthCheckPath(healthCheckPath);
        sourceRuntimeRepository.save(runtime);

        log.info("[SourceRuntime] Health check path updated for project={} id={}: {} -> {}",
                projectId, runtime.getId(), oldPath, healthCheckPath);

        return RuntimeActionResponse.builder()
                .code(CODE_SUCCESS)
                .message("Health check path updated to: " + healthCheckPath)
                .runtime(toResponse(runtime))
                .build();
    }

    // ── BaseUrl resolution for TestRun ────────────────────────────────────────

    /**
     * Resolves effective base URL for TestRun creation, in priority order:
     * <ol>
     *   <li>Explicit {@code requestBaseUrl} if non-blank (validated + normalised).</li>
     *   <li>UP SourceRuntime {@code publicBaseUrl} for the project.</li>
     *   <li>Project-level {@code defaultTargetBaseUrl} if non-blank (validated + normalised).</li>
     *   <li>400 BadRequest with clear guidance.</li>
     * </ol>
     *
     * <p>Does not require auth check — caller (TestRunServiceImpl) has already
     * verified project access before calling this.
     */
    @Override
    public String resolveBaseUrlForTestRun(UUID projectId, String requestBaseUrl, String projectDefaultTargetBaseUrl) {
        // Priority 1: explicit request URL
        String normalised = RuntimeTargetUrlValidator.normalise(requestBaseUrl);
        if (normalised != null) {
            log.debug("[SourceRuntime] BaseUrl resolved from request: {} for project={}", normalised, projectId);
            return normalised;
        }

        // Priority 2: UP SourceRuntime
        var upRuntime = sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP);
        if (upRuntime.isPresent()) {
            String runtimeUrl = upRuntime.get().getPublicBaseUrl();
            if (runtimeUrl != null && !runtimeUrl.isBlank()) {
                log.info("[SourceRuntime] BaseUrl resolved from UP SourceRuntime id={} url={} for project={}",
                        upRuntime.get().getId(), runtimeUrl, projectId);
                return runtimeUrl;
            }
        }

        // Priority 3: project default
        String projectDefault = RuntimeTargetUrlValidator.normalise(projectDefaultTargetBaseUrl);
        if (projectDefault != null) {
            log.info("[SourceRuntime] BaseUrl resolved from project defaultTargetBaseUrl={} for project={}",
                    projectDefault, projectId);
            return projectDefault;
        }

        throw new BadRequestException(
                "No runtime base URL available. " +
                "Options: (1) provide baseUrl in the request, " +
                "(2) register an External Runtime via POST /v1/source-projects/{projectId}/runtime/external, " +
                "(3) configure defaultTargetBaseUrl on the project.");
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void stopExistingUpRuntimes(UUID projectId) {
        sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP)
                .ifPresent(existing -> {
                    existing.setRuntimeStatus(RuntimeStatus.STOPPED);
                    existing.setStoppedAt(LocalDateTime.now());
                    sourceRuntimeRepository.save(existing);
                    log.info("[SourceRuntime] Stopped existing UP runtime id={} before registering new one", existing.getId());
                });
    }

    private RuntimeActionResponse runOrchestratorAction(SourceProject sourceProject, BuildStrategy strategy) {
        UUID projectId = sourceProject == null ? null : sourceProject.getId();

        if (!runtimeAutoProperties.isEnabled()) {
            return RuntimeActionResponse.builder()
                    .code(AUTO_RUNTIME_DISABLED_CODE)
                    .message(AUTO_RUNTIME_DISABLED_MESSAGE)
                    .runtime(currentOrNotCreated(projectId))
                    .build();
        }

        // Step 1: Detect source type
        RuntimeDetectionResult detection = runtimeDetectorService.detect(projectId);
        if (detection == null || !detection.isSupported()) {
            String reason = detection == null
                    ? "Runtime detector returned no result."
                    : detection.getMessage();
            SourceRuntime failed = upsertRuntime(sourceProject, detection,
                    RuntimeStatus.BUILD_FAILED, reason);
            sourceRuntimeRepository.save(failed);
            return RuntimeActionResponse.builder()
                    .code(AUTO_RUNTIME_UNSUPPORTED_CODE)
                    .message("Auto runtime cannot start: source type is not supported. Reason: " + reason)
                    .runtime(toResponse(failed))
                    .build();
        }

        // Step 2: Delegate to the strategy selected by RuntimeOrchestratorFactory.
        // Pass the BuildStrategy to the Docker orchestrator if it supports it.
        SourceRuntime result;
        var strategyImpl = orchestratorFactory.getStrategy();
        if (strategyImpl instanceof com.aitoolcheck.ai_toolcheck1_backend.service.runtime.DockerRuntimeOrchestrator docker) {
            result = docker.start(sourceProject, detection, strategy);
        } else {
            result = strategyImpl.start(sourceProject, detection);
        }

        String code;
        String message;
        if (result.getRuntimeStatus() == RuntimeStatus.UP) {
            code = CODE_SUCCESS;
            message = "Runtime started successfully. URL: " + result.getPublicBaseUrl();
        } else if (result.getRuntimeStatus() == RuntimeStatus.ENVIRONMENT_UNSUPPORTED) {
            code = CODE_ENVIRONMENT_UNSUPPORTED;
            message = result.getLastError();
        } else {
            code = AUTO_RUNTIME_NOT_IMPLEMENTED_CODE;
            message = result.getLastError() != null ? result.getLastError() : "Runtime start did not reach UP status.";
        }

        return RuntimeActionResponse.builder()
                .code(code)
                .message(message)
                .runtime(toResponse(result))
                .build();
    }

    private SourceRuntimeResponse currentOrNotCreated(UUID projectId) {
        return sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .map(this::toResponse)
                .orElseGet(() -> notCreatedResponse(projectId));
    }

    private SourceRuntime upsertRuntime(SourceProject sourceProject, RuntimeDetectionResult detection,
                                        RuntimeStatus status, String lastError) {
        SourceRuntime runtime = sourceRuntimeRepository
                .findFirstBySourceProject_IdOrderByUpdatedAtDesc(sourceProject.getId())
                .orElseGet(() -> SourceRuntime.builder()
                        .sourceProject(sourceProject)
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .build());

        runtime.setSourceProject(sourceProject);
        runtime.setRuntimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE);
        runtime.setRuntimeStatus(status);
        runtime.setRuntimeType(detection == null || detection.getRuntimeType() == null
                ? RuntimeType.UNKNOWN
                : detection.getRuntimeType());
        runtime.setDetectedPort(detection == null ? null : detection.getDetectedPort());
        runtime.setContextPath(detection == null ? null : detection.getContextPath());
        runtime.setInternalBaseUrl(null);
        runtime.setPublicBaseUrl(null);
        runtime.setLastHealthStatus(null);
        runtime.setLastError(lastError);
        return runtime;
    }

    /**
     * Performs a lightweight HTTP GET probe against provided health endpoint paths.
     *
     * @param baseUrl    the base URL to probe (no trailing slash).
     * @param paths      ordered list of paths to try.
     * @return status string: {@code "UP:<path>:<statusCode>"} or {@code "DOWN:<reason>"}.
     */
    private String probeHealth(String baseUrl, List<String> paths) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HEALTH_CHECK_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String path : paths) {
            try {
                String url = baseUrl + path;
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(HEALTH_CHECK_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return "UP:" + path + ":" + status;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "DOWN:interrupted";
            } catch (Exception e) {
                log.debug("[SourceRuntime] Health probe failed for path={}: {}", path, e.getMessage());
            }
        }
        return "DOWN:no_healthy_endpoint";
    }

    /** Convenience overload using the default paths list. */
    private String probeHealth(String baseUrl) {
        return probeHealth(baseUrl, HEALTH_CHECK_PATHS);
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private SourceRuntimeResponse notCreatedResponse(UUID projectId) {
        return SourceRuntimeResponse.builder()
                .projectId(projectId)
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(RuntimeStatus.NOT_CREATED)
                .runtimeType(RuntimeType.UNKNOWN)
                .build();
    }

    private SourceRuntimeResponse stoppedNotCreatedResponse(UUID projectId) {
        return SourceRuntimeResponse.builder()
                .projectId(projectId)
                .runtimeMode(RuntimeMode.EXTERNAL_BASE_URL)
                .runtimeStatus(RuntimeStatus.STOPPED)
                .runtimeType(RuntimeType.UNKNOWN)
                .build();
    }

    private SourceRuntimeResponse toResponse(SourceRuntime runtime) {
        return SourceRuntimeResponse.builder()
                .id(runtime.getId())
                .projectId(runtime.getSourceProject() == null ? null : runtime.getSourceProject().getId())
                .sourceVersionId(runtime.getSourceVersion() == null ? null : runtime.getSourceVersion().getId())
                .runtimeMode(runtime.getRuntimeMode())
                .runtimeStatus(runtime.getRuntimeStatus())
                .runtimeType(runtime.getRuntimeType())
                .publicBaseUrl(runtime.getPublicBaseUrl())
                .detectedPort(runtime.getDetectedPort())
                .contextPath(runtime.getContextPath())
                .healthCheckPath(runtime.getHealthCheckPath())
                .lastHealthStatus(runtime.getLastHealthStatus())
                .lastError(runtime.getLastError())
                .buildStrategyRequested(runtime.getBuildStrategyRequested())
                .buildStrategyUsed(runtime.getBuildStrategyUsed())
                .dockerfileSource(runtime.getDockerfileSource())
                .fallbackReason(runtime.getFallbackReason())
                .buildStartedAt(runtime.getBuildStartedAt())
                .buildFinishedAt(runtime.getBuildFinishedAt())
                .startedAt(runtime.getStartedAt())
                .stoppedAt(runtime.getStoppedAt())
                .createdAt(runtime.getCreatedAt())
                .updatedAt(runtime.getUpdatedAt())
                .build();
    }
}
