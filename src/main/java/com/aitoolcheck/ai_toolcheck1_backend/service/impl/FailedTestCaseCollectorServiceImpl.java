package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.common.SensitiveDataMasker;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedAssertionSnapshotDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedEndpointSnapshotDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseActualDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseAiPayload;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseExpectedDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseRequestSnapshotDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRun;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseAssertionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.FailedTestCaseCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FailedTestCaseCollectorServiceImpl implements FailedTestCaseCollectorService {

    private final TestResultRepository testResultRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final SensitiveDataMasker sensitiveDataMasker;

    @Override
    @Transactional(readOnly = true)
    public List<FailedTestCaseAiPayload> collectFailedTestCasesForAi(UUID testRunId) {
        log.info("[FailedTestCaseCollector] Start collecting failed test results for TestRun: {}", testRunId);

        Set<ResultStatus> targetStatuses = Set.of(ResultStatus.FAIL, ResultStatus.ERROR);
        
        List<TestResult> failedResults = testResultRepository.findFailedResultsWithPayloadData(testRunId, targetStatuses);
        
        if (failedResults.isEmpty()) {
            log.info("[FailedTestCaseCollector] No failed test results found for TestRun: {}", testRunId);
            return new ArrayList<>();
        }

        log.info("[FailedTestCaseCollector] Found {} failed/error results. Building payload...", failedResults.size());

        List<FailedTestCaseAiPayload> payloads = new ArrayList<>();

        for (TestResult result : failedResults) {
            try {
                FailedTestCaseAiPayload payload = buildPayload(result);
                if (payload != null) {
                    payloads.add(payload);
                }
            } catch (Exception e) {
                log.warn("[FailedTestCaseCollector] Error building payload for TestResult: {} - {}", result.getId(), e.getMessage());
            }
        }

        log.info("[FailedTestCaseCollector] Successfully built {} payloads for TestRun: {}", payloads.size(), testRunId);
        return payloads;
    }

    private FailedTestCaseAiPayload buildPayload(TestResult result) {
        TestRunItem runItem = result.getTestRunItem();
        if (runItem == null) return null;

        TestRun testRun = runItem.getTestRun();
        TestCase testCase = runItem.getTestCase();
        if (testRun == null || testCase == null) return null;

        SourceProject project = testRun.getSourceProject();
        TestCaseInput input = testCase.getTestCaseInput();

        if (input == null) {
            log.warn("[FailedTestCaseCollector] Missing TestCaseInput for TestCase: {}", testCase.getId());
            return null;
        }

        List<TestCaseAssertion> assertions = testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCase.getId());

        // 1. Build Endpoint Info
        FailedEndpointSnapshotDto endpointDto = FailedEndpointSnapshotDto.builder()
                .endpointId(testCase.getApiEndpoint() != null ? testCase.getApiEndpoint().getId() : null)
                .httpMethod(input.getHttpMethod())
                .endpointPath(input.getRequestPath())
                // Assuming we don't fetch full endpoint info deeply here to keep it simple, or populate if available
                .build();

        // 2. Build Request Snapshot
        String baseUrl = testRun.getBaseUrl() != null ? testRun.getBaseUrl() : "";
        String requestPath = input.getRequestPath() != null ? input.getRequestPath() : "";
        String fullUrl = buildFullUrl(baseUrl, requestPath);

        String maskedHeaders = sensitiveDataMasker.mask(input.getHeadersJson());
        String maskedBody = sensitiveDataMasker.mask(input.getRequestBodyJson());

        FailedTestCaseRequestSnapshotDto requestDto = FailedTestCaseRequestSnapshotDto.builder()
                .baseUrl(testRun.getBaseUrl())
                .requestPath(input.getRequestPath())
                .fullUrl(fullUrl)
                .httpMethod(input.getHttpMethod())
                .queryParamsJson(input.getQueryParamsJson())
                .headersJson(input.getHeadersJson())
                .maskedHeadersJson(maskedHeaders)
                .requestBodyJson(input.getRequestBodyJson())
                .maskedRequestBodyJson(maskedBody)
                .contentType(input.getContentType())
                .build();

        // 3. Build Expected Snapshot
        List<FailedAssertionSnapshotDto> assertionDtos = assertions.stream()
                .map(a -> FailedAssertionSnapshotDto.builder()
                        .assertionId(a.getId())
                        .assertionType(a.getAssertionType())
                        .targetPath(a.getTargetPath())
                        .operator(a.getOperator())
                        .expectedValue(a.getExpectedValue())
                        .build())
                .collect(Collectors.toList());

        FailedTestCaseExpectedDto expectedDto = FailedTestCaseExpectedDto.builder()
                .assertions(assertionDtos)
                .build();

        // 4. Build Actual Snapshot
        String maskedActualJson = sensitiveDataMasker.mask(result.getActualResponseJson());

        FailedTestCaseActualDto actualDto = FailedTestCaseActualDto.builder()
                .actualStatus(result.getActualStatus())
                .actualResponseJson(result.getActualResponseJson())
                .maskedActualResponseJson(maskedActualJson)
                .responseTimeMs(result.getResponseTimeMs())
                .errorMessage(result.getErrorMessage())
                .resultStatus(result.getResultStatus())
                .build();

        // 5. Wrap into Payload
        return FailedTestCaseAiPayload.builder()
                .projectId(project != null ? project.getId() : null)
                .projectName(project != null ? project.getProjectName() : null)
                .testRunId(testRun.getId())
                .testRunItemId(runItem.getId())
                .testResultId(result.getId())
                .testCaseId(testCase.getId())
                .testCaseName(testCase.getCaseName())
                .endpoint(endpointDto)
                .request(requestDto)
                .expected(expectedDto)
                .actual(actualDto)
                .build();
    }

    private String buildFullUrl(String baseUrl, String requestPath) {
        if (baseUrl == null || baseUrl.isEmpty()) return requestPath;
        if (requestPath == null || requestPath.isEmpty()) return baseUrl;

        boolean baseHasSlash = baseUrl.endsWith("/");
        boolean pathHasSlash = requestPath.startsWith("/");

        if (baseHasSlash && pathHasSlash) {
            return baseUrl + requestPath.substring(1);
        } else if (!baseHasSlash && !pathHasSlash) {
            return baseUrl + "/" + requestPath;
        }
        return baseUrl + requestPath;
    }
}
