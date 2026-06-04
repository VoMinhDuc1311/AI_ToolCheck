package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req.RegisterExternalRuntimeRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
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

    @PostMapping("/external")
    @Operation(
            summary = "Register external runtime",
            description = """
                    Registers a user-managed external runtime by recording its base URL.
                    AI ToolCheck will not build or start the application — the caller manages it.
                    Any previously UP external runtime for this project is stopped first.
                    The runtime is immediately marked UP after registration.
                    """,
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
            description = """
                    Probes the current runtime's public base URL with HTTP GET requests to
                    /actuator/health, /health, /healthz, / in order.
                    Updates lastHealthStatus. Does not change runtimeStatus.
                    Returns HEALTH_UP (any 2xx) or HEALTH_DOWN.
                    """,
            operationId = "healthCheckRuntime"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> healthCheck(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.healthCheckRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/start")
    @Operation(
            summary = "Start source runtime (AUTO mode skeleton)",
            description = """
                    Phase 1 skeleton: detects source type and materializes build files
                    but does NOT build or start any Docker container.
                    Returns AUTO_RUNTIME_NOT_IMPLEMENTED until Phase 2 is deployed.
                    For external runtimes, use POST /external instead.
                    """,
            operationId = "startSourceRuntime"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> startRuntime(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.startRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/rebuild")
    @Operation(
            summary = "Rebuild source runtime (AUTO mode skeleton)",
            description = "Phase 1 skeleton. Same behaviour as /start — Docker build is Phase 2.",
            operationId = "rebuildSourceRuntime"
    )
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> rebuildRuntime(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.rebuildRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/stop")
    @Operation(
            summary = "Stop runtime",
            description = """
                    Marks the current runtime as STOPPED and persists stoppedAt.
                    For EXTERNAL runtimes: records STOPPED status — does NOT terminate the actual process.
                    For AUTO runtimes (Phase 1): records STOPPED — container teardown is Phase 2.
                    Idempotent: safe to call when no runtime exists.
                    """,
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
