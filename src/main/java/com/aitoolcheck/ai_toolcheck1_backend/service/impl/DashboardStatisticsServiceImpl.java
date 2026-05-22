package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DashboardGroupBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DashboardRange;
import com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res.*;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestFailureAnalysisRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ProjectMemberRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.DashboardStatisticsService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceProjectService;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardStatisticsServiceImpl implements DashboardStatisticsService {

    private final TestResultRepository testResultRepository;
    private final AiJobLogRepository aiJobLogRepository;
    private final TestFailureAnalysisRepository testFailureAnalysisRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserService currentUserService;

    private record DashboardScope(boolean global, List<UUID> projectIds) {}

    private DashboardScope resolveDashboardScope(UUID projectId) {
        AppUser currentUser = currentUserService.getCurrentUser();

        if (currentUser.getRole() == UserRole.ADMIN) {
            if (projectId == null) {
                return new DashboardScope(true, List.of());
            }

            SourceProject project = sourceProjectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

            if (Boolean.TRUE.equals(project.getDeletedFlag())) {
                throw new ResourceNotFoundException("Project not found with id: " + projectId);
            }

            if (Boolean.TRUE.equals(project.getArchivedFlag())) {
                return new DashboardScope(false, List.of());
            }

            return new DashboardScope(false, List.of(projectId));
        }

        // MEMBER
        if (projectId != null) {
            SourceProject project = sourceProjectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

            if (Boolean.TRUE.equals(project.getDeletedFlag()) || Boolean.TRUE.equals(project.getArchivedFlag())) {
                throw new ResourceNotFoundException("Project not found with id: " + projectId);
            }

            boolean isOwner = sourceProjectRepository.existsActiveNonArchivedByIdAndOwnerUserId(projectId, currentUser.getId());
            boolean isMember = projectMemberRepository.existsBySourceProject_IdAndUser_Id(projectId, currentUser.getId());

            if (!isOwner && !isMember) {
                throw new ResourceNotFoundException("Project not found with id: " + projectId);
            }

            return new DashboardScope(false, List.of(projectId));
        }

        // MEMBER without projectId:
        List<UUID> accessibleProjectIds = sourceProjectRepository.findActiveNonArchivedOwnedOrMemberProjectIds(currentUser.getId());
        return new DashboardScope(false, accessibleProjectIds);
    }

    @Override
    public DashboardOverviewResponse getOverview(UUID projectId) {
        DashboardScope scope = resolveDashboardScope(projectId);

        if (scope.global()) {
            return buildOverviewFromGlobalQueries();
        }

        if (scope.projectIds().isEmpty()) {
            return buildZeroOverviewResponse();
        }

        return buildOverviewFromProjectScopedQueries(scope.projectIds());
    }

    private DashboardOverviewResponse buildOverviewFromGlobalQueries() {
        long totalTests = testResultRepository.count();
        List<TestResultStatusCountProjection> statusStats = testResultRepository.getStatusStatistics();
        TokenUsageProjection tokenUsage = aiJobLogRepository.getTokenUsageStatistics();
        long totalAiJobs = aiJobLogRepository.count();
        long totalFailureAnalysis = testFailureAnalysisRepository.count();

        return mapOverview(totalTests, statusStats, tokenUsage, totalAiJobs, totalFailureAnalysis);
    }

    private DashboardOverviewResponse buildOverviewFromProjectScopedQueries(Collection<UUID> projectIds) {
        long totalTests = testResultRepository.countByProjectIds(projectIds);
        List<TestResultStatusCountProjection> statusStats = testResultRepository.getStatusStatisticsByProjectIds(projectIds);
        TokenUsageProjection tokenUsage = aiJobLogRepository.getTokenUsageStatisticsByProjectIds(projectIds);
        long totalAiJobs = aiJobLogRepository.countByProjectIds(projectIds);
        long totalFailureAnalysis = testFailureAnalysisRepository.countByProjectIds(projectIds);

        return mapOverview(totalTests, statusStats, tokenUsage, totalAiJobs, totalFailureAnalysis);
    }

    private DashboardOverviewResponse buildZeroOverviewResponse() {
        return DashboardOverviewResponse.builder()
                .totalTests(0L)
                .totalPass(0L)
                .totalFail(0L)
                .totalError(0L)
                .passRate(0.0)
                .failRate(0.0)
                .errorRate(0.0)
                .totalAiJobs(0L)
                .totalTokenInput(0L)
                .totalTokenOutput(0L)
                .totalTokenUsed(0L)
                .totalFailureAnalysis(0L)
                .build();
    }

    private DashboardOverviewResponse mapOverview(long totalTests, List<TestResultStatusCountProjection> statusStats, TokenUsageProjection tokenUsage, long totalAiJobs, long totalFailureAnalysis) {
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

        return DashboardOverviewResponse.builder()
                .totalTests(totalTests)
                .totalPass(totalPass)
                .totalFail(totalFail)
                .totalError(totalError)
                .passRate(passRate)
                .failRate(failRate)
                .errorRate(errorRate)
                .totalAiJobs(totalAiJobs)
                .totalTokenInput(tokenUsage != null ? tokenUsage.getTotalInputToken() : 0L)
                .totalTokenOutput(tokenUsage != null ? tokenUsage.getTotalOutputToken() : 0L)
                .totalTokenUsed(tokenUsage != null ? tokenUsage.getTotalToken() : 0L)
                .totalFailureAnalysis(totalFailureAnalysis)
                .build();
    }

    @Override
    public List<TestResultStatusStatisticResponse> getTestResultStatistics(UUID projectId) {
        DashboardScope scope = resolveDashboardScope(projectId);

        if (scope.global()) {
            return mapTestResultStatistics(testResultRepository.count(), testResultRepository.getStatusStatistics());
        }

        if (scope.projectIds().isEmpty()) {
            return buildZeroTestResultStatistics();
        }

        return mapTestResultStatistics(
                testResultRepository.countByProjectIds(scope.projectIds()),
                testResultRepository.getStatusStatisticsByProjectIds(scope.projectIds())
        );
    }

    private List<TestResultStatusStatisticResponse> mapTestResultStatistics(long totalTests, List<TestResultStatusCountProjection> statusStats) {
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

    private List<TestResultStatusStatisticResponse> buildZeroTestResultStatistics() {
        List<TestResultStatusStatisticResponse> results = new ArrayList<>();
        for (ResultStatus status : Arrays.asList(ResultStatus.PASS, ResultStatus.FAIL, ResultStatus.ERROR)) {
            results.add(new TestResultStatusStatisticResponse(status.name(), 0L, 0.0));
        }
        return results;
    }

    @Override
    public TokenUsageStatisticResponse getTokenUsage(UUID projectId) {
        DashboardScope scope = resolveDashboardScope(projectId);

        if (scope.global()) {
            return mapTokenUsage(aiJobLogRepository.getTokenUsageStatistics());
        }

        if (scope.projectIds().isEmpty()) {
            return buildZeroTokenUsage();
        }

        return mapTokenUsage(aiJobLogRepository.getTokenUsageStatisticsByProjectIds(scope.projectIds()));
    }

    private TokenUsageStatisticResponse mapTokenUsage(TokenUsageProjection stats) {
        return TokenUsageStatisticResponse.builder()
                .totalInputToken(stats != null ? stats.getTotalInputToken() : 0L)
                .totalOutputToken(stats != null ? stats.getTotalOutputToken() : 0L)
                .totalToken(stats != null ? stats.getTotalToken() : 0L)
                .build();
    }

    private TokenUsageStatisticResponse buildZeroTokenUsage() {
        return TokenUsageStatisticResponse.builder()
                .totalInputToken(0L)
                .totalOutputToken(0L)
                .totalToken(0L)
                .build();
    }

    @Override
    public List<AiJobStatisticResponse> getAiJobStatistics(UUID projectId) {
        DashboardScope scope = resolveDashboardScope(projectId);

        if (scope.global()) {
            return mapAiJobStatistics(aiJobLogRepository.getAiJobStatistics());
        }

        if (scope.projectIds().isEmpty()) {
            return Collections.emptyList();
        }

        return mapAiJobStatistics(aiJobLogRepository.getAiJobStatisticsByProjectIds(scope.projectIds()));
    }

    private List<AiJobStatisticResponse> mapAiJobStatistics(List<AiJobStatisticProjection> stats) {
        return stats.stream()
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
    public List<FailureAnalysisTypeStatisticResponse> getFailureAnalysisStatistics(UUID projectId) {
        DashboardScope scope = resolveDashboardScope(projectId);

        if (scope.global()) {
            return mapFailureAnalysisStatistics(testFailureAnalysisRepository.getFailureTypeStatistics());
        }

        if (scope.projectIds().isEmpty()) {
            return Collections.emptyList();
        }

        return mapFailureAnalysisStatistics(testFailureAnalysisRepository.getFailureTypeStatisticsByProjectIds(scope.projectIds()));
    }

    private List<FailureAnalysisTypeStatisticResponse> mapFailureAnalysisStatistics(List<FailureAnalysisTypeProjection> stats) {
        return stats.stream()
                .map(p -> new FailureAnalysisTypeStatisticResponse(
                        p.getFailureType() != null ? p.getFailureType() : "UNKNOWN",
                        p.getTotal()))
                .collect(Collectors.toList());
    }

    @Override
    public List<FailurePriorityStatisticResponse> getFailurePriorityStatistics(UUID projectId) {
        DashboardScope scope = resolveDashboardScope(projectId);

        if (scope.global()) {
            return mapFailurePriorityStatistics(testFailureAnalysisRepository.getPriorityStatistics());
        }

        if (scope.projectIds().isEmpty()) {
            return Collections.emptyList();
        }

        return mapFailurePriorityStatistics(testFailureAnalysisRepository.getPriorityStatisticsByProjectIds(scope.projectIds()));
    }

    private List<FailurePriorityStatisticResponse> mapFailurePriorityStatistics(List<FailurePriorityProjection> stats) {
        return stats.stream()
                .map(p -> new FailurePriorityStatisticResponse(
                        p.getPriority() != null ? p.getPriority() : "UNKNOWN",
                        p.getTotal()))
                .collect(Collectors.toList());
    }

    @Override
    public List<RecentFailureAnalysisResponse> getRecentFailures(UUID projectId, int limit) {
        DashboardScope scope = resolveDashboardScope(projectId);

        if (scope.global()) {
            return mapRecentFailures(testFailureAnalysisRepository.findRecentAnalyses(PageRequest.of(0, limit)));
        }

        if (scope.projectIds().isEmpty()) {
            return Collections.emptyList();
        }

        return mapRecentFailures(testFailureAnalysisRepository.findRecentAnalysesByProjectIds(scope.projectIds(), PageRequest.of(0, limit)));
    }

    private List<RecentFailureAnalysisResponse> mapRecentFailures(List<TestFailureAnalysis> analyses) {
        return analyses.stream()
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

    @Override
    public TestResultTimelineResponse getTestResultTimeline(
            DashboardRange range,
            DashboardGroupBy groupBy,
            UUID projectId,
            String timezone,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        // 1. Validate timezone
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception e) {
            throw new BadRequestException("Invalid timezone: " + timezone);
        }

        // Get current date in that timezone:
        LocalDate today = LocalDate.now(zoneId);

        LocalDate resolvedFromDate = null;
        LocalDate resolvedToDate = null;

        // 2. Resolve date range
        if (range == null) {
            range = DashboardRange.LAST_7_DAYS;
        }

        switch (range) {
            case LAST_7_DAYS -> {
                resolvedFromDate = today.minusDays(6);
                resolvedToDate = today;
            }
            case LAST_30_DAYS -> {
                resolvedFromDate = today.minusDays(29);
                resolvedToDate = today;
            }
            case THIS_QUARTER -> {
                int quarter = (today.getMonthValue() - 1) / 3 + 1;
                resolvedFromDate = LocalDate.of(today.getYear(), (quarter - 1) * 3 + 1, 1);
                resolvedToDate = today;
            }
            case CUSTOM -> {
                if (fromDate == null || toDate == null) {
                    throw new BadRequestException("fromDate and toDate are required when range is CUSTOM");
                }
                if (fromDate.isAfter(toDate)) {
                    throw new BadRequestException("fromDate cannot be after toDate");
                }
                long days = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate);
                if (days > 366) {
                    throw new BadRequestException("Date range is too large. Maximum supported range is 1 year.");
                }
                resolvedFromDate = fromDate;
                resolvedToDate = toDate;
            }
        }

        // 3. Determine effective groupBy
        DashboardGroupBy effectiveGroupBy = groupBy;
        if (groupBy == null || groupBy == DashboardGroupBy.AUTO) {
            switch (range) {
                case LAST_7_DAYS, LAST_30_DAYS -> effectiveGroupBy = DashboardGroupBy.DAY;
                case THIS_QUARTER -> effectiveGroupBy = DashboardGroupBy.WEEK;
                case CUSTOM -> {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(resolvedFromDate, resolvedToDate);
                    if (days <= 31) {
                        effectiveGroupBy = DashboardGroupBy.DAY;
                    } else if (days <= 180) {
                        effectiveGroupBy = DashboardGroupBy.WEEK;
                    } else {
                        effectiveGroupBy = DashboardGroupBy.MONTH;
                    }
                }
            }
        }

        // 4. Resolve security scope
        DashboardScope scope = resolveDashboardScope(projectId);

        List<TestResultTimelineItemResponse> items = new ArrayList<>();

        // Generate empty buckets based on effectiveGroupBy
        if (effectiveGroupBy == DashboardGroupBy.DAY) {
            LocalDate current = resolvedFromDate;
            while (!current.isAfter(resolvedToDate)) {
                int dayOfWeek = current.getDayOfWeek().getValue();
                String dayLabel;
                switch (current.getDayOfWeek()) {
                    case MONDAY -> dayLabel = "Thứ 2";
                    case TUESDAY -> dayLabel = "Thứ 3";
                    case WEDNESDAY -> dayLabel = "Thứ 4";
                    case THURSDAY -> dayLabel = "Thứ 5";
                    case FRIDAY -> dayLabel = "Thứ 6";
                    case SATURDAY -> dayLabel = "Thứ 7";
                    case SUNDAY -> dayLabel = "Chủ nhật";
                    default -> dayLabel = "";
                }
                String dateLabel = String.format("%02d/%02d", current.getDayOfMonth(), current.getMonthValue());

                items.add(TestResultTimelineItemResponse.builder()
                        .bucketStartDate(current)
                        .bucketEndDate(current)
                        .dateLabel(dateLabel)
                        .dayOfWeek(dayOfWeek)
                        .dayLabel(dayLabel)
                        .total(0L)
                        .passCount(0L)
                        .failCount(0L)
                        .errorCount(0L)
                        .skippedCount(0L)
                        .build());

                current = current.plusDays(1);
            }
        } else if (effectiveGroupBy == DashboardGroupBy.WEEK) {
            LocalDate currentMonday = resolvedFromDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            LocalDate lastSunday = resolvedToDate.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY));

            LocalDate temp = currentMonday;
            while (!temp.isAfter(lastSunday)) {
                LocalDate weekStart = temp;
                LocalDate weekEnd = temp.plusDays(6);
                String dateLabel = String.format("Tuần %02d/%02d - %02d/%02d",
                        weekStart.getDayOfMonth(), weekStart.getMonthValue(),
                        weekEnd.getDayOfMonth(), weekEnd.getMonthValue());

                items.add(TestResultTimelineItemResponse.builder()
                        .bucketStartDate(weekStart)
                        .bucketEndDate(weekEnd)
                        .dateLabel(dateLabel)
                        .dayOfWeek(null)
                        .dayLabel(null)
                        .total(0L)
                        .passCount(0L)
                        .failCount(0L)
                        .errorCount(0L)
                        .skippedCount(0L)
                        .build());

                temp = temp.plusWeeks(1);
            }
        } else if (effectiveGroupBy == DashboardGroupBy.MONTH) {
            LocalDate currentFirstDay = resolvedFromDate.withDayOfMonth(1);
            LocalDate lastFirstDay = resolvedToDate.withDayOfMonth(1);

            LocalDate temp = currentFirstDay;
            while (!temp.isAfter(lastFirstDay)) {
                LocalDate monthStart = temp;
                LocalDate monthEnd = temp.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
                String dateLabel = String.format("%02d/%d", monthStart.getMonthValue(), monthStart.getYear());

                items.add(TestResultTimelineItemResponse.builder()
                        .bucketStartDate(monthStart)
                        .bucketEndDate(monthEnd)
                        .dateLabel(dateLabel)
                        .dayOfWeek(null)
                        .dayLabel(null)
                        .total(0L)
                        .passCount(0L)
                        .failCount(0L)
                        .errorCount(0L)
                        .skippedCount(0L)
                        .build());

                temp = temp.plusMonths(1);
            }
        }

        // Map for fast merging
        Map<LocalDate, TestResultTimelineItemResponse> bucketMap = new HashMap<>();
        for (TestResultTimelineItemResponse item : items) {
            bucketMap.put(item.getBucketStartDate(), item);
        }

        // 5. Query and Merge data if scope is valid
        if (!scope.global() && scope.projectIds().isEmpty()) {
            return TestResultTimelineResponse.builder()
                    .range(range)
                    .groupBy(effectiveGroupBy)
                    .timezone(timezone)
                    .fromDate(resolvedFromDate)
                    .toDate(resolvedToDate)
                    .items(items)
                    .build();
        }

        // Convert query boundaries to LocalDateTime (based on the user's timezone)
        java.time.LocalDateTime startDateTime = resolvedFromDate.atStartOfDay(zoneId).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        java.time.LocalDateTime endDateTime = resolvedToDate.atTime(23, 59, 59, 999999999).atZone(zoneId).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

        List<TestResultTimelineProjection> dbResults;
        if (scope.global()) {
            dbResults = testResultRepository.findTimelineDataGlobal(startDateTime, endDateTime);
        } else {
            dbResults = testResultRepository.findTimelineDataByProjectIds(scope.projectIds(), startDateTime, endDateTime);
        }

        // Merge DB results into buckets
        for (TestResultTimelineProjection proj : dbResults) {
            LocalDate recordDate = proj.getCreatedAt()
                    .atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(zoneId)
                    .toLocalDate();

            TestResultTimelineItemResponse bucket = null;
            if (effectiveGroupBy == DashboardGroupBy.DAY) {
                bucket = bucketMap.get(recordDate);
            } else if (effectiveGroupBy == DashboardGroupBy.WEEK) {
                LocalDate recordMonday = recordDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                bucket = bucketMap.get(recordMonday);
            } else if (effectiveGroupBy == DashboardGroupBy.MONTH) {
                LocalDate recordFirstDay = recordDate.withDayOfMonth(1);
                bucket = bucketMap.get(recordFirstDay);
            }

            if (bucket != null) {
                ResultStatus status = proj.getResultStatus();
                if (status == ResultStatus.PASS) bucket.setPassCount(bucket.getPassCount() + 1);
                else if (status == ResultStatus.FAIL) bucket.setFailCount(bucket.getFailCount() + 1);
                else if (status == ResultStatus.ERROR) bucket.setErrorCount(bucket.getErrorCount() + 1);
                else if (status == ResultStatus.SKIPPED) bucket.setSkippedCount(bucket.getSkippedCount() + 1);
                bucket.setTotal(bucket.getTotal() + 1);
            }
        }

        return TestResultTimelineResponse.builder()
                .range(range)
                .groupBy(effectiveGroupBy)
                .timezone(timezone)
                .fromDate(resolvedFromDate)
                .toDate(resolvedToDate)
                .items(items)
                .build();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
