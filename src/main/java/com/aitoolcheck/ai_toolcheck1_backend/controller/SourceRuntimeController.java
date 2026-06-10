package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req.RegisterExternalRuntimeRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req.StartRuntimeRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/source-projects/{projectId}/runtime")
@RequiredArgsConstructor
@Tag(name = "Source Runtime", description = "Runtime management APIs for source projects")
public class SourceRuntimeController {

    private final SourceRuntimeService sourceRuntimeService;

    @GetMapping
    @Operation(
            summary = "Get current runtime",
            description = "Returns the most recent runtime record for the project, or NOT_CREATED if none exists.",
            operationId = "getSourceRuntime"
    )
    public ResponseEntity<ApiResponse<SourceRuntimeResponse>> getRuntime(@PathVariable UUID projectId) {
        return ResponseEntity.ok(success("Runtime fetched successfully.",
                sourceRuntimeService.getCurrentRuntime(projectId)));
    }

    @GetMapping("/all")
    @Operation(
            summary = "List all runtimes",
            description = "Returns all runtime records for the project, newest first.",
            operationId = "listSourceRuntimes"
    )
    public ResponseEntity<ApiResponse<List<SourceRuntimeResponse>>> listRuntimes(@PathVariable UUID projectId) {
        return ResponseEntity.ok(success("Runtimes fetched successfully.",
                sourceRuntimeService.listRuntimes(projectId)));
    }

    @GetMapping("/environment")
    @Operation(
            summary = "Get host environment capabilities",
            description = "Probes the backend host for Docker CLI, Docker socket, JDK, Maven, Gradle, and writable temp dir. "
                    + "Returns a summary explaining what is available and what infrastructure change is needed for autostart. "
                    + "On the current EC2 deployment (eclipse-temurin:21-jre, no docker.sock mount), canAutoStart will be false.",
            operationId = "getEnvironmentCapabilities"
    )
    public ResponseEntity<ApiResponse<EnvironmentCapabilityReport>> getEnvironment(@PathVariable UUID projectId) {
        return ResponseEntity.ok(success("Environment capabilities probed.",
                sourceRuntimeService.getEnvironmentCapabilities()));
    }

    @PostMapping("/external")
    @Operation(
            summary = "Register external runtime",
            description = "Registers a user-managed external runtime by recording its base URL. "
                    + "Any previously UP external runtime for this project is stopped first. "
                    + "The runtime is immediately marked UP after registration.",
            operationId = "registerExternalRuntime"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> registerExternal(
            @PathVariable UUID projectId,
            @Valid @RequestBody RegisterExternalRuntimeRequest request) {
        RuntimeActionResponse data = sourceRuntimeService.registerExternalRuntime(projectId, request);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/health")
    @Operation(
            summary = "Health check runtime",
            description = "Probes the current runtime's public base URL. "
                    + "If a custom healthCheckPath is set (via POST /health-check-path), it is probed first. "
                    + "Otherwise tries /actuator/health, /health, /healthz, / in order. "
                    + "Updates lastHealthStatus. Does not change runtimeStatus. "
                    + "Returns HEALTH_UP (any 2xx) or HEALTH_DOWN.",
            operationId = "healthCheckRuntime"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> healthCheck(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.healthCheckRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/health-check-path")
    @Operation(
            summary = "Set custom health check path",
            description = "Sets a custom health-check path for this project's runtime. "
                    + "Example: /greeting, /api/ping, /actuator/health. "
                    + "Fixes the Phase 1 issue where health check returned HEALTH_DOWN for apps "
                    + "with non-standard endpoints. After setting /greeting, POST /health probes that path first.",
            operationId = "updateHealthCheckPath"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> updateHealthCheckPath(
            @PathVariable UUID projectId,
            @RequestParam String path) {
        RuntimeActionResponse data = sourceRuntimeService.updateHealthCheckPath(projectId, path);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/start")
    @Operation(
            summary = "Start source runtime (AUTO mode)",
            description = "Phase 2: Probes the host environment and attempts to start an auto-runtime. "
                    + "Accepts an optional body or query param to select the build strategy: "
                    + "AUTO (default), UPLOADED_DOCKERFILE_ONLY, GENERATED_DOCKERFILE, AUTO_WITH_FALLBACK. "
                    + "Example: POST /runtime/start?buildStrategy=GENERATED_DOCKERFILE. "
                    + "NEVER returns runtimeStatus=UP unless the container is genuinely reachable.",
            operationId = "startSourceRuntime"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> startRuntime(
            @PathVariable UUID projectId,
            @RequestBody(required = false) StartRuntimeRequest body,
            @RequestParam(required = false) String buildStrategy,
            @RequestParam(required = false, defaultValue = "false") Boolean wait) {

        BuildStrategy strategy = resolveStrategy(buildStrategy, body);
        boolean shouldWait = (wait != null && wait) || (body != null && body.getWait() != null && body.getWait());

        RuntimeActionResponse data = sourceRuntimeService.startRuntime(projectId, strategy);

        if (shouldWait && data.getRuntime() != null && data.getRuntime().getId() != null) {
            data = sourceRuntimeService.waitForRuntimeTerminalState(projectId, data.getRuntime().getId(), 300);
        }

        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    BuildStrategy resolveStrategy(String queryParam, StartRuntimeRequest body) {
        // Query param takes precedence over body
        if (queryParam != null && !queryParam.isBlank()) {
            try {
                return BuildStrategy.valueOf(queryParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(
                        "Invalid buildStrategy: '" + queryParam + "'. "
                        + "Valid values: AUTO, UPLOADED_DOCKERFILE_ONLY, GENERATED_DOCKERFILE, AUTO_WITH_FALLBACK");
            }
        }
        if (body != null && body.getBuildStrategy() != null) {
            return body.getBuildStrategy();
        }
        return BuildStrategy.AUTO;
    }

    @PostMapping("/rebuild")
    @Operation(
            summary = "Rebuild and restart source runtime (AUTO mode)",
            description = "Stops the existing runtime (if any) then starts a fresh build via the environment-appropriate orchestrator.",
            operationId = "rebuildSourceRuntime"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> rebuildRuntime(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.rebuildRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/stop")
    @Operation(
            summary = "Stop runtime",
            description = "Marks the current runtime as STOPPED. "
                    + "For EXTERNAL runtimes: records status only, does NOT terminate the process. "
                    + "For AUTO runtimes with an active container: terminates the Docker container. "
                    + "Idempotent: safe to call when no runtime exists.",
            operationId = "stopSourceRuntime"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> stopRuntime(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.stopRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private <T> ApiResponse<T> success(String message, T data) {
        return response("SUCCESS", message, data);
    }

    private <T> ApiResponse<T> response(String code, String message, T data) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
