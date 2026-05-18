package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res.*;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestFailureAnalysisRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.DashboardStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardStatisticsServiceImpl implements DashboardStatisticsService {

    private final TestResultRepository testResultRepository;
    private final AiJobLogRepository aiJobLogRepository;
    private final TestFailureAnalysisRepository testFailureAnalysisRepository;

    @Override
    public DashboardOverviewResponse getOverview() {
        long totalTests = testResultRepository.count();
        List<TestResultStatusCountProjection> statusStats = testResultRepository.getStatusStatistics();

        long totalPass = 0;
        long totalFail = 0;
        long totalError = 0;

        for (TestResultStatusCountProjection stat : statusStats) {
            if (stat.getStatus() == ResultStatus.PASS) totalPass = stat.getTotal();
            else if (stat.getStatus() == ResultStatus.FAIL) totalFail = stat.getTotal();
            else if (stat.getStatus() == ResultStatus.ERROR) totalError = stat.getTotal();
        }

        double passRate = totalTests > 0 ? round((double) totalPass * 100 / totalTests) : 0;
        double failRate = totalTests > 0 ? round((double) totalFail * 100 / totalTests) : 0;
        double errorRate = totalTests > 0 ? round((double) totalError * 100 / totalTests) : 0;

        TokenUsageProjection tokenUsage = aiJobLogRepository.getTokenUsageStatistics();
        long totalAiJobs = aiJobLogRepository.count();
        long totalFailureAnalysis = testFailureAnalysisRepository.count();

        return DashboardOverviewResponse.builder()
                .totalTests(totalTests)
                .totalPass(totalPass)
                .totalFail(totalFail)
                .totalError(totalError)
                .passRate(passRate)
                .failRate(failRate)
                .errorRate(errorRate)
                .totalAiJobs(totalAiJobs)
                .totalTokenInput(tokenUsage != null ? tokenUsage.getTotalInputToken() : 0)
                .totalTokenOutput(tokenUsage != null ? tokenUsage.getTotalOutputToken() : 0)
                .totalTokenUsed(tokenUsage != null ? tokenUsage.getTotalToken() : 0)
                .totalFailureAnalysis(totalFailureAnalysis)
                .build();
    }

    @Override
    public List<TestResultStatusStatisticResponse> getTestResultStatistics() {
        long totalTests = testResultRepository.count();
        List<TestResultStatusCountProjection> statusStats = testResultRepository.getStatusStatistics();
        
        Map<ResultStatus, Long> statsMap = new EnumMap<>(ResultStatus.class);
        for (TestResultStatusCountProjection stat : statusStats) {
            statsMap.put(stat.getStatus(), stat.getTotal());
        }

        List<TestResultStatusStatisticResponse> results = new ArrayList<>();
        for (ResultStatus status : Arrays.asList(ResultStatus.PASS, ResultStatus.FAIL, ResultStatus.ERROR)) {
            long total = statsMap.getOrDefault(status, 0L);
            double percentage = totalTests > 0 ? round((double) total * 100 / totalTests) : 0;
            results.add(new TestResultStatusStatisticResponse(status.name(), total, percentage));
        }

        return results;
    }

    @Override
    public TokenUsageStatisticResponse getTokenUsage() {
        TokenUsageProjection stats = aiJobLogRepository.getTokenUsageStatistics();
        return TokenUsageStatisticResponse.builder()
                .totalInputToken(stats != null ? stats.getTotalInputToken() : 0)
                .totalOutputToken(stats != null ? stats.getTotalOutputToken() : 0)
                .totalToken(stats != null ? stats.getTotalToken() : 0)
                .build();
    }

    @Override
    public List<AiJobStatisticResponse> getAiJobStatistics() {
        return aiJobLogRepository.getAiJobStatistics().stream()
                .map(p -> AiJobStatisticResponse.builder()
                        .jobType(p.getJobType() != null ? p.getJobType().name() : "UNKNOWN")
                        .executionStatus(p.getExecutionStatus() != null ? p.getExecutionStatus().name() : "UNKNOWN")
                        .totalJobs(p.getTotalJobs())
                        .totalInputToken(p.getTotalInputToken())
                        .totalOutputToken(p.getTotalOutputToken())
                        .totalToken(p.getTotalToken())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<FailureAnalysisTypeStatisticResponse> getFailureAnalysisStatistics() {
        return testFailureAnalysisRepository.getFailureTypeStatistics().stream()
                .map(p -> new FailureAnalysisTypeStatisticResponse(
                        p.getFailureType() != null ? p.getFailureType() : "UNKNOWN",
                        p.getTotal()))
                .collect(Collectors.toList());
    }

    @Override
    public List<FailurePriorityStatisticResponse> getFailurePriorityStatistics() {
        return testFailureAnalysisRepository.getPriorityStatistics().stream()
                .map(p -> new FailurePriorityStatisticResponse(
                        p.getPriority() != null ? p.getPriority() : "UNKNOWN",
                        p.getTotal()))
                .collect(Collectors.toList());
    }

    @Override
    public List<RecentFailureAnalysisResponse> getRecentFailures(int limit) {
        return testFailureAnalysisRepository.findRecentAnalyses(PageRequest.of(0, limit)).stream()
                .map(this::mapToRecentFailureAnalysisResponse)
                .collect(Collectors.toList());
    }

    private RecentFailureAnalysisResponse mapToRecentFailureAnalysisResponse(TestFailureAnalysis entity) {
        return RecentFailureAnalysisResponse.builder()
                .analysisId(entity.getId())
                .testResultId(entity.getTestResult() != null ? entity.getTestResult().getId() : null)
                .failureType(entity.getFailureType())
                .summary(entity.getSummary())
                .rootCause(entity.getRootCause())
                .recommendedNextAction(entity.getRecommendedNextAction())
                .priority(entity.getPriority())
                .confidence(entity.getConfidence())
                .modelName(entity.getModelName())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
