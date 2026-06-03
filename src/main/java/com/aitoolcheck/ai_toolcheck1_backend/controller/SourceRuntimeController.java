package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v1/source-projects/{projectId}/runtime")
@RequiredArgsConstructor
@Tag(name = "Source Runtime", description = "Uploaded source runtime skeleton APIs")
public class SourceRuntimeController {

    private final SourceRuntimeService sourceRuntimeService;

    @GetMapping
    @Operation(summary = "Get source runtime", description = "Get current runtime status for a project.", operationId = "getSourceRuntime")
    public ResponseEntity<ApiResponse<SourceRuntimeResponse>> getRuntime(@PathVariable UUID projectId) {
        return ResponseEntity.ok(success("Runtime fetched successfully.", sourceRuntimeService.getCurrentRuntime(projectId)));
    }

    @PostMapping("/start")
    @Operation(summary = "Start source runtime", description = "Skeleton endpoint. Docker runtime is not implemented in Phase 1.", operationId = "startSourceRuntime")
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> startRuntime(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.startRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/rebuild")
    @Operation(summary = "Rebuild source runtime", description = "Skeleton endpoint. Docker runtime is not implemented in Phase 1.", operationId = "rebuildSourceRuntime")
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> rebuildRuntime(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.rebuildRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

    @PostMapping("/stop")
    @Operation(summary = "Stop source runtime", description = "Safe no-op in Phase 1 when no runtime exists.", operationId = "stopSourceRuntime")
    public ResponseEntity<ApiResponse<RuntimeActionResponse>> stopRuntime(@PathVariable UUID projectId) {
        RuntimeActionResponse data = sourceRuntimeService.stopRuntime(projectId);
        return ResponseEntity.ok(response(data.getCode(), data.getMessage(), data));
    }

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
