package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res.*;

import java.util.List;

public interface DashboardStatisticsService {
    DashboardOverviewResponse getOverview();
    List<TestResultStatusStatisticResponse> getTestResultStatistics();
    TokenUsageStatisticResponse getTokenUsage();
    List<AiJobStatisticResponse> getAiJobStatistics();
    List<FailureAnalysisTypeStatisticResponse> getFailureAnalysisStatistics();
    List<FailurePriorityStatisticResponse> getFailurePriorityStatistics();
    List<RecentFailureAnalysisResponse> getRecentFailures(int limit);
}
