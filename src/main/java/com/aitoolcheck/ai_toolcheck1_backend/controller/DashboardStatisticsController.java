package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res.*;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DashboardGroupBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DashboardRange;
import com.aitoolcheck.ai_toolcheck1_backend.service.DashboardStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/dashboard/statistics")
@RequiredArgsConstructor
@Tag(name = "Dashboard Statistics", description = "Dashboard statistics and visualization APIs")
public class DashboardStatisticsController {

    private final DashboardStatisticsService dashboardStatisticsService;

    @GetMapping("/overview")
    @Operation(summary = "Get dashboard overview statistics")
    public ResponseEntity<ApiResponse<DashboardOverviewResponse>> getOverview(
            @RequestParam(required = false) UUID projectId) {
        DashboardOverviewResponse data = dashboardStatisticsService.getOverview(projectId);
        return ResponseEntity.ok(success("Dashboard overview fetched successfully", data));
    }

    @GetMapping("/test-results")
    @Operation(summary = "Get test result status statistics")
    public ResponseEntity<ApiResponse<List<TestResultStatusStatisticResponse>>> getTestResultStatistics(
            @RequestParam(required = false) UUID projectId) {
        List<TestResultStatusStatisticResponse> data = dashboardStatisticsService.getTestResultStatistics(projectId);
        return ResponseEntity.ok(success("Test result statistics fetched successfully", data));
    }

    @GetMapping("/test-results/timeline")
    @Operation(summary = "Get test result timeline statistics for charts")
    public ResponseEntity<ApiResponse<TestResultTimelineResponse>> getTestResultTimeline(
            @RequestParam(defaultValue = "LAST_7_DAYS") DashboardRange range,
            @RequestParam(required = false) DashboardGroupBy groupBy,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "Asia/Ho_Chi_Minh") String timezone,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        TestResultTimelineResponse data = dashboardStatisticsService.getTestResultTimeline(
                range, groupBy, projectId, timezone, fromDate, toDate);
        return ResponseEntity.ok(success("Test result timeline fetched successfully", data));
    }

    @GetMapping("/token-usage")
    @Operation(summary = "Get AI token usage statistics")
    public ResponseEntity<ApiResponse<TokenUsageStatisticResponse>> getTokenUsage(
            @RequestParam(required = false) UUID projectId) {
        TokenUsageStatisticResponse data = dashboardStatisticsService.getTokenUsage(projectId);
        return ResponseEntity.ok(success("Token usage statistics fetched successfully", data));
    }

    @GetMapping("/ai-jobs")
    @Operation(summary = "Get AI job statistics grouped by type and status")
    public ResponseEntity<ApiResponse<List<AiJobStatisticResponse>>> getAiJobStatistics(
            @RequestParam(required = false) UUID projectId) {
        List<AiJobStatisticResponse> data = dashboardStatisticsService.getAiJobStatistics(projectId);
        return ResponseEntity.ok(success("AI job statistics fetched successfully", data));
    }

    @GetMapping("/failure-analysis")
    @Operation(summary = "Get failure analysis statistics grouped by type")
    public ResponseEntity<ApiResponse<List<FailureAnalysisTypeStatisticResponse>>> getFailureAnalysisStatistics(
            @RequestParam(required = false) UUID projectId) {
        List<FailureAnalysisTypeStatisticResponse> data = dashboardStatisticsService.getFailureAnalysisStatistics(projectId);
        return ResponseEntity.ok(success("Failure analysis statistics fetched successfully", data));
    }

    @GetMapping("/failure-priority")
    @Operation(summary = "Get failure analysis statistics grouped by priority")
    public ResponseEntity<ApiResponse<List<FailurePriorityStatisticResponse>>> getFailurePriorityStatistics(
            @RequestParam(required = false) UUID projectId) {
        List<FailurePriorityStatisticResponse> data = dashboardStatisticsService.getFailurePriorityStatistics(projectId);
        return ResponseEntity.ok(success("Failure priority statistics fetched successfully", data));
    }

    @GetMapping("/recent-failures")
    @Operation(summary = "Get recent AI failure analyses")
    public ResponseEntity<ApiResponse<List<RecentFailureAnalysisResponse>>> getRecentFailures(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "10") int limit) {
        List<RecentFailureAnalysisResponse> data = dashboardStatisticsService.getRecentFailures(projectId, limit);
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
