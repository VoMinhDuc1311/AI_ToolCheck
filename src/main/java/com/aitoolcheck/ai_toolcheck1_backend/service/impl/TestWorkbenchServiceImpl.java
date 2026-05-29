package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.res.TestCaseAssertionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.res.TestCaseInputResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res.TestFailureAnalysisDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.res.TestResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrunitem.res.TestRunItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testworkbench.res.TestWorkbenchResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestWorkbenchService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class TestWorkbenchServiceImpl implements TestWorkbenchService {

    private final TestCaseRepository testCaseRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final TestRunRepository testRunRepository;
    private final TestRunItemRepository testRunItemRepository;
    private final TestFailureAnalysisRepository testFailureAnalysisRepository;
    private final ProjectAccessService projectAccessService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public TestWorkbenchResponse getTestWorkbenchData(UUID projectId) {
        // Auth check
        projectAccessService.requireCanViewProject(projectId);

        // Fetch counts for summary metrics
        long totalTestCases = testCaseRepository.countBySourceProject_IdAndDeletedFlagFalse(projectId);
        long activeTestCases = testCaseRepository.countBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalse(projectId);
        long aiGenerated = testCaseRepository.countBySourceProject_IdAndGeneratedByAndDeletedFlagFalse(projectId, GeneratedBy.AI);
        long userGenerated = testCaseRepository.countBySourceProject_IdAndGeneratedByAndDeletedFlagFalse(projectId, GeneratedBy.USER);
        long totalAssertions = testCaseAssertionRepository.countAssertionsByProjectId(projectId);
        long totalRuns = testRunRepository.countBySourceProject_Id(projectId);
        long completedRuns = testRunRepository.countBySourceProject_IdAndRunStatus(projectId, RunStatus.COMPLETED);
        long failedRuns = testRunRepository.countBySourceProject_IdAndRunStatus(projectId, RunStatus.FAILED);

        // Fetch all runs to determine latest status and list
        List<TestRun> runs = testRunRepository.findBySourceProject_IdOrderByCreatedAtDesc(projectId);
        String latestRunStatus = runs.isEmpty() ? "NONE" : runs.get(0).getRunStatus().name();

        TestWorkbenchResponse.Summary summary = TestWorkbenchResponse.Summary.builder()
                .totalTestCases(totalTestCases)
                .activeTestCases(activeTestCases)
                .aiGenerated(aiGenerated)
                .userGenerated(userGenerated)
                .totalAssertions(totalAssertions)
                .totalRuns(totalRuns)
                .completedRuns(completedRuns)
                .failedRuns(failedRuns)
                .latestRunStatus(latestRunStatus)
                .build();

        // Load test cases
        List<TestCase> testCases = testCaseRepository.findBySourceProject_IdAndDeletedFlagFalseOrderByUpdatedAtDesc(projectId);
        List<TestCaseDetailResponse> testCaseResponses = testCases.stream()
                .map(this::toTestCaseDetailResponse)
                .toList();

        // Load runs and map with items/results
        List<TestRunDetailResponse> testRunResponses = runs.stream()
                .map(run -> {
                    List<TestRunItem> items = testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(run.getId());
                    return toTestRunDetailResponse(run, items);
                })
                .toList();

        return TestWorkbenchResponse.builder()
                .projectId(projectId)
                .summary(summary)
                .testCases(testCaseResponses)
                .testRuns(testRunResponses)
                .build();
    }

    private TestCaseDetailResponse toTestCaseDetailResponse(TestCase testCase) {
        List<TestCaseAssertionResponse> assertionResponses = testCase.getTestCaseAssertions() == null
                ? List.of()
                : testCase.getTestCaseAssertions()
                        .stream()
                        .sorted(Comparator.comparing(
                                TestCaseAssertion::getSortOrder,
                                Comparator.nullsLast(Integer::compareTo)))
                        .map(this::toAssertionResponse)
                        .toList();

        return TestCaseDetailResponse.builder()
                .id(testCase.getId())
                .projectId(testCase.getSourceProject().getId())
                .apiEndpointId(testCase.getApiEndpoint() == null ? null : testCase.getApiEndpoint().getId())
                .apiDocumentVersionId(
                        testCase.getApiDocumentVersion() == null ? null : testCase.getApiDocumentVersion().getId())
                .caseCode(testCase.getCaseCode())
                .caseName(testCase.getCaseName())
                .description(testCase.getDescription())
                .caseType(testCase.getCaseType())
                .priorityLevel(testCase.getPriorityLevel())
                .generatedBy(testCase.getGeneratedBy())
                .activeFlag(testCase.getActiveFlag())
                .deletedFlag(testCase.getDeletedFlag())
                .requiresWrite(testCase.getRequiresWrite())
                .cleanupRequired(testCase.getCleanupRequired())
                .input(toInputResponse(testCase.getTestCaseInput()))
                .assertions(assertionResponses)
                .apiEndpoint(toApiEndpointResponse(testCase.getApiEndpoint()))
                .createdAt(testCase.getCreatedAt())
                .updatedAt(testCase.getUpdatedAt())
                .deletedAt(testCase.getDeletedAt())
                .build();
    }

    private TestCaseInputResponse toInputResponse(TestCaseInput input) {
        if (input == null) {
            return null;
        }
        return TestCaseInputResponse.builder()
                .id(input.getId())
                .testCaseId(input.getTestCase() == null ? null : input.getTestCase().getId())
                .httpMethod(input.getHttpMethod())
                .requestPath(input.getRequestPath())
                .queryParamsJson(toJsonMap(input.getQueryParamsJson()))
                .headersJson(toJsonMap(input.getHeadersJson()))
                .requestBodyJson(toJsonMap(input.getRequestBodyJson()))
                .contentType(input.getContentType())
                .timeoutMs(input.getTimeoutMs())
                .inputData(input.getInputData())
                .createdAt(input.getCreatedAt())
                .updatedAt(input.getUpdatedAt())
                .build();
    }

    private TestCaseAssertionResponse toAssertionResponse(TestCaseAssertion assertion) {
        return TestCaseAssertionResponse.builder()
                .id(assertion.getId())
                .testCaseId(assertion.getTestCase() == null ? null : assertion.getTestCase().getId())
                .assertionType(assertion.getAssertionType())
                .targetPath(assertion.getTargetPath())
                .operator(assertion.getOperator())
                .expectedValue(assertion.getExpectedValue())
                .enabledFlag(assertion.getEnabledFlag())
                .sortOrder(assertion.getSortOrder())
                .createdAt(assertion.getCreatedAt())
                .updatedAt(assertion.getUpdatedAt())
                .build();
    }

    private ApiEndpointResponse toApiEndpointResponse(ApiEndpoint apiEndpoint) {
        if (apiEndpoint == null) {
            return null;
        }
        return ApiEndpointResponse.builder()
                .id(apiEndpoint.getId())
                .projectId(apiEndpoint.getSourceProject().getId())
                .sourceFileId(apiEndpoint.getSourceFile() == null ? null : apiEndpoint.getSourceFile().getId())
                .sourceUploadVersionId(apiEndpoint.getSourceUploadVersion() == null ? null : apiEndpoint.getSourceUploadVersion().getId())
                .controllerName(apiEndpoint.getControllerName())
                .methodName(apiEndpoint.getMethodName())
                .httpMethod(apiEndpoint.getHttpMethod())
                .endpointPath(apiEndpoint.getEndpointPath())
                .stableKey(apiEndpoint.getStableKey())
                .description(apiEndpoint.getDescription())
                .operationId(apiEndpoint.getOperationId())
                .tagName(apiEndpoint.getTagName())
                .authRequired(apiEndpoint.getAuthRequired())
                .deprecatedFlag(apiEndpoint.getDeprecatedFlag())
                .activeFlag(apiEndpoint.getActiveFlag())
                .staleFlag(apiEndpoint.getStaleFlag())
                .aiEnrichedFlag(apiEndpoint.getAiEnrichedFlag())
                .aiSummary(apiEndpoint.getAiSummary())
                .aiDescription(apiEndpoint.getAiDescription())
                .aiEnrichedAt(apiEndpoint.getAiEnrichedAt())
                .build();
    }

    private TestRunDetailResponse toTestRunDetailResponse(TestRun run, List<TestRunItem> items) {
        List<TestRunItemResponse> itemResponses = items.stream()
                .map(this::toItemResponse)
                .toList();

        return TestRunDetailResponse.builder()
                .id(run.getId())
                .projectId(run.getSourceProject().getId())
                .runCode(run.getRunCode())
                .runName(run.getRunName())
                .description(run.getDescription())
                .baseUrl(run.getBaseUrl())
                .environmentName(run.getEnvironmentName())
                .executionMode(run.getExecutionMode())
                .runStatus(run.getRunStatus())
                .totalItems(itemResponses.size())
                .items(itemResponses)
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }

    private TestRunItemResponse toItemResponse(TestRunItem item) {
        TestCase testCase = item.getTestCase();
        TestResult testResult = item.getTestResult();

        return TestRunItemResponse.builder()
                .id(item.getId())
                .testRunId(item.getTestRun().getId())
                .testCaseId(testCase.getId())
                .caseCode(testCase.getCaseCode())
                .caseName(testCase.getCaseName())
                .sortOrder(item.getSortOrder())
                .itemStatus(item.getItemStatus())
                .result(toResultResponse(testResult))
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }

    private TestResultResponse toResultResponse(TestResult result) {
        if (result == null) {
            return null;
        }

        String actualResponseJson = result.getActualResponseJson();

        TestFailureAnalysis failureAnalysis = testFailureAnalysisRepository
                .findFirstByTestResult_IdOrderByCreatedAtDesc(result.getId())
                .orElse(null);

        return TestResultResponse.builder()
                .id(result.getId())
                .testRunItemId(result.getTestRunItem().getId())
                .actualStatus(result.getActualStatus())
                .resultStatus(result.getResultStatus())
                .responseTimeMs(result.getResponseTimeMs())
                .actualResponseJson(actualResponseJson)
                .errorMessage(result.getErrorMessage())
                .blockedReason(result.getBlockedReason())
                .failureAnalysis(toFailureAnalysisResponse(failureAnalysis))
                .createdAt(result.getCreatedAt())
                .updatedAt(result.getUpdatedAt())
                .build();
    }

    private TestFailureAnalysisDetailResponse toFailureAnalysisResponse(TestFailureAnalysis tfa) {
        if (tfa == null) {
            return null;
        }
        return TestFailureAnalysisDetailResponse.builder()
                .id(tfa.getId())
                .testResultId(tfa.getTestResult().getId())
                .aiJobLogId(tfa.getAiJobLog() == null ? null : tfa.getAiJobLog().getId())
                .modelName(tfa.getModelName())
                .failureType(tfa.getFailureType())
                .summary(tfa.getSummary())
                .rootCause(tfa.getRootCause())
                .expectedBehavior(tfa.getExpectedBehavior())
                .actualBehavior(tfa.getActualBehavior())
                .isLikelyBackendBug(tfa.getIsLikelyBackendBug())
                .isLikelyTestCaseBug(tfa.getIsLikelyTestCaseBug())
                .suggestedFixesJson(tfa.getSuggestedFixesJson())
                .recommendedNextAction(tfa.getRecommendedNextAction())
                .confidence(tfa.getConfidence())
                .priority(tfa.getPriority())
                .createdAt(tfa.getCreatedAt())
                .updatedAt(tfa.getUpdatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> toJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, java.util.Map.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
