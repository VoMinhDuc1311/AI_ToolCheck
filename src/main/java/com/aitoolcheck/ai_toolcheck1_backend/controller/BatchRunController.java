package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.CreateBatchRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunReportResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.BatchRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/batch-runs")
@RequiredArgsConstructor
@Tag(name = "Batch Runs", description = "Batch autorun orchestration APIs")
public class BatchRunController {

    private final BatchRunService batchRunService;

    @PostMapping
    @Operation(summary = "Create batch run", operationId = "createBatchRun")
    public ResponseEntity<ApiResponse<BatchRunResponse>> create(
            @Valid @RequestBody CreateBatchRunRequest request) {
        BatchRunResponse data = batchRunService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(success("Batch run created successfully.", data));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Start batch run", operationId = "startBatchRun")
    public ResponseEntity<ApiResponse<BatchRunResponse>> start(@PathVariable UUID id) {
        BatchRunResponse data = batchRunService.start(id);
        return ResponseEntity.ok(success("Batch run started successfully.", data));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel batch run", operationId = "cancelBatchRun")
    public ResponseEntity<ApiResponse<BatchRunResponse>> cancel(@PathVariable UUID id) {
        BatchRunResponse data = batchRunService.cancel(id);
        return ResponseEntity.ok(success("Batch run cancelled successfully.", data));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get batch run", operationId = "getBatchRunById")
    public ResponseEntity<ApiResponse<BatchRunResponse>> getById(@PathVariable UUID id) {
        BatchRunResponse data = batchRunService.getById(id);
        return ResponseEntity.ok(success("Batch run fetched successfully.", data));
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "List batch run items", operationId = "listBatchRunItems")
    public ResponseEntity<ApiResponse<List<BatchRunItemResponse>>> getItems(@PathVariable UUID id) {
        List<BatchRunItemResponse> data = batchRunService.getItems(id);
        return ResponseEntity.ok(success("Batch run items fetched successfully.", data));
    }

    @GetMapping("/{id}/report")
    @Operation(summary = "Get batch run report", operationId = "getBatchRunReport")
    public ResponseEntity<ApiResponse<BatchRunReportResponse>> getReport(@PathVariable UUID id) {
        BatchRunReportResponse data = batchRunService.getReport(id);
        return ResponseEntity.ok(success("Batch run report fetched successfully.", data));
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code("SUCCESS")
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
