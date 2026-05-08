package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.HttpActualResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestResultService;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestResultServiceImpl implements TestResultService {

    private final TestResultRepository testResultRepository;

    @Override
    public RuleEngineResultDto evaluateAssertions(HttpActualResponseDto actualResponse, List<TestCaseAssertion> assertions) {
        log.info("Evaluating assertions for status code: {}", actualResponse.getStatusCode());

        if (actualResponse.getStatusCode() == null || actualResponse.getStatusCode() == 0) {
            return RuleEngineResultDto.builder()
                    .finalStatus(ResultStatus.ERROR)
                    .logDetails("Network error or timeout: " + actualResponse.getErrorMessage())
                    .build();
        }

        StringBuilder logBuilder = new StringBuilder();
        ResultStatus finalStatus = ResultStatus.PASS;

        if (assertions == null || assertions.isEmpty()) {
            logBuilder.append("No assertions defined for this test case. Defaulting to PASS.");
        } else {
            for (TestCaseAssertion assertion : assertions) {
                if (assertion.getEnabledFlag() != null && !assertion.getEnabledFlag()) {
                    continue;
                }

                boolean assertionPassed = false;
                String actualValueStr = "N/A";
                String detail = "";

                try {
                    switch (assertion.getAssertionType()) {
                        case STATUS_CODE:
                            int actualStatus = actualResponse.getStatusCode();
                            actualValueStr = String.valueOf(actualStatus);
                            assertionPassed = compareValues(actualStatus, assertion.getExpectedValue(), assertion.getOperator());
                            break;

                        case RESPONSE_TIME:
                        case RESPONSE_TIME_MS:
                            long actualTime = actualResponse.getResponseTimeMs() != null ? actualResponse.getResponseTimeMs() : 0L;
                            actualValueStr = String.valueOf(actualTime);
                            assertionPassed = compareValues(actualTime, assertion.getExpectedValue(), assertion.getOperator());
                            break;

                        case JSON_PATH:
                            if (actualResponse.getResponseBody() == null || actualResponse.getResponseBody().isBlank()) {
                                detail = "Response body is empty";
                                assertionPassed = false;
                            } else {
                                try {
                                    Object actualJsonValue = JsonPath.read(actualResponse.getResponseBody(), assertion.getTargetPath());
                                    actualValueStr = actualJsonValue != null ? actualJsonValue.toString() : "null";
                                    assertionPassed = compareValues(actualJsonValue, assertion.getExpectedValue(), assertion.getOperator());
                                } catch (PathNotFoundException e) {
                                    detail = "Path not found: " + assertion.getTargetPath();
                                    assertionPassed = false;
                                } catch (IllegalArgumentException e) {
                                    detail = "Invalid JsonPath or body: " + e.getMessage();
                                    assertionPassed = false;
                                }
                            }
                            break;

                        default:
                            detail = "Unsupported assertion type: " + assertion.getAssertionType();
                            assertionPassed = false;
                            break;
                    }
                } catch (Exception e) {
                    detail = "Error evaluating assertion: " + e.getMessage();
                    assertionPassed = false;
                }

                if (!assertionPassed) {
                    finalStatus = ResultStatus.FAIL;
                }

                logBuilder.append(String.format("[%s] %s: Expected %s %s %s. Actual: %s. %s\n",
                        assertionPassed ? "PASS" : "FAIL",
                        assertion.getAssertionType(),
                        assertion.getTargetPath() != null ? assertion.getTargetPath() : "",
                        assertion.getOperator(),
                        assertion.getExpectedValue(),
                        actualValueStr,
                        detail));
            }
        }

        return RuleEngineResultDto.builder()
                .finalStatus(finalStatus)
                .logDetails(logBuilder.toString())
                .build();
    }

    private boolean compareValues(Object actual, String expected, ComparisonOperator operator) {
        if (operator == ComparisonOperator.IS_NOT_NULL) return actual != null;
        if (operator == ComparisonOperator.IS_NULL) return actual == null;

        String actualStr = actual != null ? actual.toString() : null;

        switch (operator) {
            case EQUALS:
                return Objects.equals(actualStr, expected);
            case NOT_EQUALS:
                return !Objects.equals(actualStr, expected);
            case CONTAINS:
                return actualStr != null && expected != null && actualStr.contains(expected);
            case NOT_CONTAINS:
                return actualStr != null && expected != null && !actualStr.contains(expected);
            case GREATER_THAN:
                return compareNumeric(actualStr, expected) > 0;
            case GREATER_THAN_OR_EQUALS:
                return compareNumeric(actualStr, expected) >= 0;
            case LESS_THAN:
                return compareNumeric(actualStr, expected) < 0;
            case LESS_THAN_OR_EQUALS:
                return compareNumeric(actualStr, expected) <= 0;
            default:
                log.warn("Operator {} not fully implemented in compareValues", operator);
                return false;
        }
    }

    private int compareNumeric(String actual, String expected) {
        try {
            Double dActual = Double.parseDouble(actual);
            Double dExpected = Double.parseDouble(expected);
            return dActual.compareTo(dExpected);
        } catch (Exception e) {
            return 0; 
        }
    }

    @Override
    @Transactional
    public void saveTestResult(TestRunItem item, HttpActualResponseDto actualResponse, RuleEngineResultDto ruleResult) {
        TestResult result = testResultRepository.findByTestRunItem_Id(item.getId())
                .orElse(new TestResult());

        result.setTestRunItem(item);
        result.setActualStatus(actualResponse.getStatusCode());
        result.setActualResponseJson(actualResponse.getResponseBody());
        result.setResultStatus(ruleResult.getFinalStatus());
        result.setResponseTimeMs(actualResponse.getResponseTimeMs() != null ? actualResponse.getResponseTimeMs().intValue() : 0);
        result.setErrorMessage(actualResponse.getErrorMessage());
        result.setBlockedReason(ruleResult.getLogDetails());

        testResultRepository.save(result);
        log.info("Saved test result for TestRunItem {}: Status={}", item.getId(), ruleResult.getFinalStatus());
    }
}
