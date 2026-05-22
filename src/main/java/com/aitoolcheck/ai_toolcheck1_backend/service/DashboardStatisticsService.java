package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res.*;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DashboardGroupBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DashboardRange;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DashboardStatisticsService {
    DashboardOverviewResponse getOverview(UUID projectId);

    List<TestResultStatusStatisticResponse> getTestResultStatistics(UUID projectId);

    TokenUsageStatisticResponse getTokenUsage(UUID projectId);

    List<AiJobStatisticResponse> getAiJobStatistics(UUID projectId);

    List<FailureAnalysisTypeStatisticResponse> getFailureAnalysisStatistics(UUID projectId);

    List<FailurePriorityStatisticResponse> getFailurePriorityStatistics(UUID projectId);

    List<RecentFailureAnalysisResponse> getRecentFailures(UUID projectId, int limit);

    TestResultTimelineResponse getTestResultTimeline(
            DashboardRange range,
            DashboardGroupBy groupBy,
            UUID projectId,
            String timezone,
            LocalDate fromDate,
            LocalDate toDate
    );
}
