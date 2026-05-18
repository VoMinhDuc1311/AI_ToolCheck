package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.DashboardStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/v1/dashboard/statistics")
@RequiredArgsConstructor
@Tag(name = "Dashboard Statistics", description = "Dashboard statistics and visualization APIs")
public class DashboardStatisticsController {

    private final DashboardStatisticsService dashboardStatisticsService;

    @GetMapping("/overview")
    @Operation(summary = "Get dashboard overview statistics")
    public ResponseEntity<ApiResponse<DashboardOverviewResponse>> getOverview() {
        DashboardOverviewResponse data = dashboardStatisticsService.getOverview();
        return ResponseEntity.ok(success("Dashboard overview fetched successfully", data));
    }

    @GetMapping("/test-results")
    @Operation(summary = "Get test result status statistics")
    public ResponseEntity<ApiResponse<List<TestResultStatusStatisticResponse>>> getTestResultStatistics() {
        List<TestResultStatusStatisticResponse> data = dashboardStatisticsService.getTestResultStatistics();
        return ResponseEntity.ok(success("Test result statistics fetched successfully", data));
    }

    @GetMapping("/token-usage")
    @Operation(summary = "Get AI token usage statistics")
    public ResponseEntity<ApiResponse<TokenUsageStatisticResponse>> getTokenUsage() {
        TokenUsageStatisticResponse data = dashboardStatisticsService.getTokenUsage();
        return ResponseEntity.ok(success("Token usage statistics fetched successfully", data));
    }

    @GetMapping("/ai-jobs")
    @Operation(summary = "Get AI job statistics grouped by type and status")
    public ResponseEntity<ApiResponse<List<AiJobStatisticResponse>>> getAiJobStatistics() {
        List<AiJobStatisticResponse> data = dashboardStatisticsService.getAiJobStatistics();
        return ResponseEntity.ok(success("AI job statistics fetched successfully", data));
    }

    @GetMapping("/failure-analysis")
    @Operation(summary = "Get failure analysis statistics grouped by type")
    public ResponseEntity<ApiResponse<List<FailureAnalysisTypeStatisticResponse>>> getFailureAnalysisStatistics() {
        List<FailureAnalysisTypeStatisticResponse> data = dashboardStatisticsService.getFailureAnalysisStatistics();
        return ResponseEntity.ok(success("Failure analysis statistics fetched successfully", data));
    }

    @GetMapping("/failure-priority")
    @Operation(summary = "Get failure analysis statistics grouped by priority")
    public ResponseEntity<ApiResponse<List<FailurePriorityStatisticResponse>>> getFailurePriorityStatistics() {
        List<FailurePriorityStatisticResponse> data = dashboardStatisticsService.getFailurePriorityStatistics();
        return ResponseEntity.ok(success("Failure priority statistics fetched successfully", data));
    }

    @GetMapping("/recent-failures")
    @Operation(summary = "Get recent AI failure analyses")
    public ResponseEntity<ApiResponse<List<RecentFailureAnalysisResponse>>> getRecentFailures(
            @RequestParam(defaultValue = "10") int limit) {
        List<RecentFailureAnalysisResponse> data = dashboardStatisticsService.getRecentFailures(limit);
        return ResponseEntity.ok(success("Recent failure analyses fetched successfully", data));
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
