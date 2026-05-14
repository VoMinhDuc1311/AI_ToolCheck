package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.req.AnalyzeFailuresRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res.AnalyzeFailuresResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestFailureAnalysisOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v1/test-runs")
@RequiredArgsConstructor
@Tag(name = "Test Failure Analysis", description = "AI failure analysis APIs for test runs")
public class TestFailureAnalysisController {

    private final TestFailureAnalysisOrchestratorService orchestratorService;

    @PostMapping("/{testRunId}/failure-analysis/analyze")
    @Operation(
            summary = "Analyze failed test cases",
            description = "Run AI failure analysis for all failed/error test cases in a test run",
            operationId = "analyzeFailuresByTestRun"
    )
    public ResponseEntity<ApiResponse<AnalyzeFailuresResponse>> analyzeFailures(
            @PathVariable UUID testRunId,
            @Valid @RequestBody(required = false) AnalyzeFailuresRequest request) {
        
        AnalyzeFailuresResponse data = orchestratorService.analyzeFailuresByTestRun(testRunId, request);

        return ResponseEntity.ok(success("Failure analysis batch completed.", data));
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
