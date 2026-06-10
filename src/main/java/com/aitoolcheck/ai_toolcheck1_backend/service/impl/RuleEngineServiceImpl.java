package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.AssertionEvaluationResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseAssertionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuleEngineService;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuleEngineServiceImpl implements RuleEngineService {

    private final TestResultRepository testResultRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public RuleEngineResultDto evaluate(UUID testResultId) {
        TestResult testResult = testResultRepository.findById(testResultId)
                .orElseThrow(() -> new ResourceNotFoundException("TestResult not found: " + testResultId));
        return evaluate(testResult);
    }

    @Override
    @Transactional
    public RuleEngineResultDto evaluate(TestResult testResult) {
        log.debug("[RuleEngine] Starting evaluate for testResultId={}",
                testResult != null ? testResult.getId() : "null");
        if (testResult == null) {
            throw new IllegalArgumentException("TestResult cannot be null");
        }

        TestRunItem testRunItem = testResult.getTestRunItem();
        if (testRunItem == null) {
            return buildErrorResult(testResult, null, "TestResult does not have an associated TestRunItem");
        }

        TestCase testCase = testRunItem.getTestCase();
        if (testCase == null) {
            return buildErrorResult(testResult, null, "TestRunItem does not have an associated TestCase");
        }

        if (testResult.getActualStatus() != null && testResult.getActualStatus() == 0) {
            return buildNetworkErrorResult(testResult, testCase.getId());
        }

        List<TestCaseAssertion> assertions = testCaseAssertionRepository
                .findByTestCase_IdOrderBySortOrderAsc(testCase.getId());

        if (assertions == null || assertions.isEmpty()) {
            log.warn("[RuleEngine] testResultId={}, testCaseId={}, message=No assertions found", testResult.getId(),
                    testCase.getId());
            return buildErrorResult(testResult, testCase.getId(),
                    "No assertions found for test case " + testCase.getId());
        }

        List<AssertionEvaluationResultDto> assertionResults = new ArrayList<>();
        int passedCount = 0;
        int failedCount = 0;

        for (TestCaseAssertion assertion : assertions) {
            AssertionEvaluationResultDto resultDto = evaluateAssertion(assertion, testResult);
            assertionResults.add(resultDto);
            if (resultDto.isPassed()) {
                passedCount++;
            } else {
                failedCount++;
            }
        }

        ResultStatus finalStatus;
        String summaryMessage;

        if (failedCount == 0) {
            finalStatus = ResultStatus.PASS;
            summaryMessage = "All assertions passed";
        } else {
            finalStatus = ResultStatus.FAIL;
            StringBuilder sb = new StringBuilder();
            sb.append(failedCount).append("/").append(assertions.size()).append(" assertions failed: ");
            boolean first = true;
            for (AssertionEvaluationResultDto res : assertionResults) {
                if (!res.isPassed()) {
                    if (!first)
                        sb.append("; ");
                    sb.append(res.getMessage());
                    first = false;
                }
            }
            summaryMessage = sb.toString();
        }

        testResult.setResultStatus(finalStatus);
        testResult.setErrorMessage(finalStatus == ResultStatus.FAIL ? summaryMessage : null);
        testResultRepository.save(testResult);

        log.info("[RuleEngine] Finished evaluate for testResultId={}, finalStatus={}, passed={}, failed={}",
                testResult.getId(), finalStatus, passedCount, failedCount);

        return RuleEngineResultDto.builder()
                .testResultId(testResult.getId())
                .testCaseId(testCase.getId())
                .finalStatus(finalStatus)
                .totalAssertions(assertions.size())
                .passedAssertions(passedCount)
                .failedAssertions(failedCount)
                .assertionResults(assertionResults)
                .summaryMessage(summaryMessage)
                .build();
    }

    private AssertionEvaluationResultDto evaluateAssertion(TestCaseAssertion assertion, TestResult testResult) {
        Object actualValue = null;
        boolean isError = false;
        String errorMessage = null;

        try {
            actualValue = extractActualValue(assertion, testResult);
        } catch (Exception e) {
            isError = true;
            errorMessage = e.getMessage();
        }

        boolean passed = false;
        String message;

        if (isError) {
            passed = false;
            message = "Error extracting value: " + errorMessage;
        } else {
            try {
                passed = compare(actualValue, assertion.getExpectedValue(), assertion.getOperator());
                if (passed) {
                    message = "Assertion passed";
                } else {
                    message = String.format("Expected %s %s %s but actual was %s",
                            assertion.getAssertionType(),
                            assertion.getOperator(),
                            assertion.getExpectedValue() == null ? "null" : assertion.getExpectedValue(),
                            actualValue == null ? "null" : actualValue.toString());
                }
            } catch (Exception e) {
                passed = false;
                message = "Comparison error: " + e.getMessage();
            }
        }

        if (!passed) {
            log.debug("[RuleEngine] Assertion failed: testResultId={}, assertionId={}, message={}", testResult.getId(),
                    assertion.getId(), message);
        }

        return AssertionEvaluationResultDto.builder()
                .assertionId(assertion.getId())
                .assertionType(assertion.getAssertionType())
                .targetPath(assertion.getTargetPath())
                .operator(assertion.getOperator())
                .expectedValue(assertion.getExpectedValue())
                .actualValue(actualValue != null ? actualValue.toString() : null)
                .passed(passed)
                .message(message)
                .build();
    }

    private Object extractActualValue(TestCaseAssertion assertion, TestResult testResult) {
        AssertionType type = assertion.getAssertionType();
        if (type == null)
            return null;

        switch (type) {
            case STATUS_CODE:
                return testResult.getActualStatus();
            case JSON_PATH:
                String json = testResult.getActualResponseJson();
                if (json == null || json.trim().isEmpty()) {
                    return null;
                }
                String targetPath = assertion.getTargetPath();
                if (targetPath == null || targetPath.trim().isEmpty()) {
                    return null;
                }
                try {
                    return JsonPath.read(json, targetPath);
                } catch (PathNotFoundException e) {
                    return null;
                }
            case BODY_CONTAINS:
                return testResult.getActualResponseJson();
            case RESPONSE_TIME:
            case RESPONSE_TIME_MS:
                return testResult.getResponseTimeMs();
            case ERROR_MESSAGE:
                return testResult.getErrorMessage();
            case HEADER:
                return extractHeaderValue(assertion.getTargetPath(), testResult.getActualResponseHeadersJson());
            case BODY_NOT_NULL:
                return testResult.getActualResponseJson();
            default:
                return null;
        }
    }

    private boolean compare(Object actualValue, String expectedValue, ComparisonOperator operator) {
        if (operator == null)
            return false;

        switch (operator) {
            case EQUALS:
                if (actualValue == null && expectedValue == null)
                    return true;
                if (actualValue == null || expectedValue == null)
                    return false;
                return String.valueOf(actualValue).trim().equals(expectedValue.trim());

            case NOT_EQUALS:
                if (actualValue == null && expectedValue == null)
                    return false;
                if (actualValue == null || expectedValue == null)
                    return true;
                return !String.valueOf(actualValue).trim().equals(expectedValue.trim());

            case CONTAINS:
                if (actualValue == null || expectedValue == null)
                    return false;
                return String.valueOf(actualValue).contains(expectedValue);

            case NOT_CONTAINS:
                if (actualValue == null)
                    return true;
                if (expectedValue == null)
                    return false;
                return !String.valueOf(actualValue).contains(expectedValue);

            case GREATER_THAN:
            case GREATER_THAN_OR_EQUALS:
            case LESS_THAN:
            case LESS_THAN_OR_EQUALS:
                if (actualValue == null || expectedValue == null)
                    return false;
                BigDecimal actualNum;
                BigDecimal expectedNum;
                try {
                    actualNum = new BigDecimal(String.valueOf(actualValue).trim());
                    expectedNum = new BigDecimal(expectedValue.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Cannot compare non-numeric values: " + actualValue + " vs " + expectedValue);
                }
                int cmp = actualNum.compareTo(expectedNum);
                if (operator == ComparisonOperator.GREATER_THAN)
                    return cmp > 0;
                if (operator == ComparisonOperator.GREATER_THAN_OR_EQUALS)
                    return cmp >= 0;
                if (operator == ComparisonOperator.LESS_THAN)
                    return cmp < 0;
                if (operator == ComparisonOperator.LESS_THAN_OR_EQUALS)
                    return cmp <= 0;
                return false;

            case IS_NULL:
            case NOT_EXISTS:
                return actualValue == null;

            case IS_NOT_NULL:
            case EXISTS:
                return actualValue != null;

            case MATCHES_REGEX:
                if (actualValue == null || expectedValue == null)
                    return false;
                try {
                    return String.valueOf(actualValue).matches(expectedValue);
                } catch (java.util.regex.PatternSyntaxException e) {
                    log.warn("[RuleEngine] Invalid regex pattern: {}", expectedValue);
                    throw new IllegalArgumentException("Invalid regex pattern: " + expectedValue);
                }

            default:
                return false;
        }
    }

    private RuleEngineResultDto buildErrorResult(TestResult testResult, UUID testCaseId, String errorMessage) {
        log.error("[RuleEngine] Rule Engine Error: testResultId={}, testCaseId={}, message={}",
                testResult.getId(), testCaseId, errorMessage);
        testResult.setResultStatus(ResultStatus.ERROR);
        testResult.setErrorMessage(errorMessage);
        testResultRepository.save(testResult);

        return RuleEngineResultDto.builder()
                .testResultId(testResult.getId())
                .testCaseId(testCaseId)
                .finalStatus(ResultStatus.ERROR)
                .totalAssertions(0)
                .passedAssertions(0)
                .failedAssertions(0)
                .assertionResults(new ArrayList<>())
                .summaryMessage("Rule Engine error: " + errorMessage)
                .build();
    }

    private RuleEngineResultDto buildNetworkErrorResult(TestResult testResult, UUID testCaseId) {
        String errorMessage = hasText(testResult.getErrorMessage())
                ? testResult.getErrorMessage()
                : "Network error from target";

        log.warn("[RuleEngine] Target network error: testResultId={}, testCaseId={}, message={}",
                testResult.getId(), testCaseId, errorMessage);

        testResult.setResultStatus(ResultStatus.ERROR);
        testResult.setErrorMessage(errorMessage);
        testResultRepository.save(testResult);

        return RuleEngineResultDto.builder()
                .testResultId(testResult.getId())
                .testCaseId(testCaseId)
                .finalStatus(ResultStatus.ERROR)
                .totalAssertions(0)
                .passedAssertions(0)
                .failedAssertions(0)
                .assertionResults(new ArrayList<>())
                .summaryMessage(errorMessage)
                .build();
    }

    /**
     * Extracts a specific header value from the stored JSON string.
     *
     * <p>Header name lookup is case-insensitive (HTTP/1.1 spec).
     * If {@code actualResponseHeadersJson} is null or blank (e.g. network error or legacy record),
     * returns a sentinel string that will cause the assertion to FAIL (not ERROR).
     *
     * @param headerName              the header name from {@code assertion.targetPath}
     * @param actualResponseHeadersJson JSON string of captured headers, may be null
     * @return the header value if found; null if header name not present; or a FAIL-sentinel if headers unavailable
     */
    private Object extractHeaderValue(String headerName, String actualResponseHeadersJson) {
        if (headerName == null || headerName.isBlank()) {
            log.warn("[RuleEngine] HEADER assertion targetPath (header name) is blank");
            return null;
        }

        if (actualResponseHeadersJson == null || actualResponseHeadersJson.isBlank()) {
            // Headers were not captured (network error or legacy TestResult).
            // Return a special sentinel — compare() will produce FAIL, not ERROR or exception.
            log.warn("[RuleEngine] HEADER assertion: actualResponseHeadersJson is null — headers not captured");
            return "__HEADERS_NOT_CAPTURED__";
        }

        try {
            Map<String, String> headers = objectMapper.readValue(
                    actualResponseHeadersJson,
                    new TypeReference<Map<String, String>>() {});

            // Case-insensitive lookup
            String lowerTarget = headerName.trim().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(lowerTarget)) {
                    return entry.getValue();
                }
            }
            // Header name not found in response
            log.debug("[RuleEngine] HEADER assertion: header '{}' not found in response headers", headerName);
            return null;
        } catch (Exception e) {
            log.warn("[RuleEngine] HEADER assertion: failed to parse actualResponseHeadersJson: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
