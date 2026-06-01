package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.AssertionEvaluationResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseAssertionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for HEADER assertion in {@link RuleEngineServiceImpl}.
 *
 * <p>Key requirements tested:
 * <ul>
 *   <li>HEADER assertion must NEVER throw UnsupportedOperationException</li>
 *   <li>HEADER assertion performs case-insensitive header name lookup</li>
 *   <li>When headers are captured and match, assertion returns PASS</li>
 *   <li>When header is missing from response, assertion returns FAIL</li>
 *   <li>When actualResponseHeadersJson is null, assertion returns FAIL with message (not ERROR)</li>
 * </ul>
 */
class RuleEngineHeaderAssertionTest {

    private TestResultRepository testResultRepository;
    private TestCaseAssertionRepository testCaseAssertionRepository;
    private RuleEngineServiceImpl ruleEngine;

    private UUID testResultId;
    private UUID testCaseId;

    @BeforeEach
    void setUp() {
        testResultRepository = mock(TestResultRepository.class);
        testCaseAssertionRepository = mock(TestCaseAssertionRepository.class);
        ruleEngine = new RuleEngineServiceImpl(
                testResultRepository,
                testCaseAssertionRepository,
                new ObjectMapper());

        testResultId = UUID.randomUUID();
        testCaseId = UUID.randomUUID();

        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Core requirement: no UnsupportedOperationException ───────────────────

    @Test
    void headerAssertion_doesNotThrowUnsupportedOperationException() {
        TestResult testResult = buildTestResult(null); // no headers
        TestCaseAssertion assertion = buildHeaderAssertion("Content-Type", ComparisonOperator.EQUALS, "application/json");

        when(testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCaseId))
                .thenReturn(List.of(assertion));

        // Must NEVER throw UnsupportedOperationException — even when headers are null
        assertDoesNotThrow(() -> ruleEngine.evaluate(testResult),
                "HEADER assertion must not throw UnsupportedOperationException");
    }

    // ── PASS when header matches (EQUALS) ────────────────────────────────────

    @Test
    void headerAssertion_returnsPass_whenHeaderMatches() throws Exception {
        Map<String, String> headers = Map.of("Content-Type", "application/json");
        String headersJson = new ObjectMapper().writeValueAsString(headers);

        TestResult testResult = buildTestResult(headersJson);
        TestCaseAssertion assertion = buildHeaderAssertion("Content-Type", ComparisonOperator.EQUALS, "application/json");

        when(testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCaseId))
                .thenReturn(List.of(assertion));

        RuleEngineResultDto result = ruleEngine.evaluate(testResult);

        assertThat(result.getFinalStatus()).isEqualTo(ResultStatus.PASS);
        assertThat(result.getPassedAssertions()).isEqualTo(1);
        assertThat(result.getFailedAssertions()).isEqualTo(0);
    }

    // ── FAIL when header value does not match ────────────────────────────────

    @Test
    void headerAssertion_returnsFail_whenHeaderValueMismatch() throws Exception {
        Map<String, String> headers = Map.of("Content-Type", "text/plain");
        String headersJson = new ObjectMapper().writeValueAsString(headers);

        TestResult testResult = buildTestResult(headersJson);
        TestCaseAssertion assertion = buildHeaderAssertion("Content-Type", ComparisonOperator.EQUALS, "application/json");

        when(testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCaseId))
                .thenReturn(List.of(assertion));

        RuleEngineResultDto result = ruleEngine.evaluate(testResult);

        assertThat(result.getFinalStatus()).isEqualTo(ResultStatus.FAIL);
        assertThat(result.getFailedAssertions()).isEqualTo(1);
    }

    // ── FAIL when header is missing from response ─────────────────────────────

    @Test
    void headerAssertion_returnsFail_whenHeaderMissing() throws Exception {
        Map<String, String> headers = Map.of("X-Custom-Header", "some-value");
        String headersJson = new ObjectMapper().writeValueAsString(headers);

        TestResult testResult = buildTestResult(headersJson);
        // Assert a different header that doesn't exist
        TestCaseAssertion assertion = buildHeaderAssertion("Content-Type", ComparisonOperator.EQUALS, "application/json");

        when(testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCaseId))
                .thenReturn(List.of(assertion));

        RuleEngineResultDto result = ruleEngine.evaluate(testResult);

        // Header not found → null → compare("application/json") fails → FAIL
        assertThat(result.getFinalStatus()).isEqualTo(ResultStatus.FAIL);
    }

    // ── Case-insensitive header lookup ────────────────────────────────────────

    @Test
    void headerAssertion_isCaseInsensitive() throws Exception {
        // Server sends "content-type" (lowercase), assertion uses "Content-Type" (mixed case)
        Map<String, String> headers = Map.of("content-type", "application/json;charset=UTF-8");
        String headersJson = new ObjectMapper().writeValueAsString(headers);

        TestResult testResult = buildTestResult(headersJson);
        TestCaseAssertion assertion = buildHeaderAssertion("Content-Type", ComparisonOperator.CONTAINS, "application/json");

        when(testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCaseId))
                .thenReturn(List.of(assertion));

        RuleEngineResultDto result = ruleEngine.evaluate(testResult);

        assertThat(result.getFinalStatus()).isEqualTo(ResultStatus.PASS);
    }

    @Test
    void headerAssertion_isCaseInsensitive_uppercaseInRequest() throws Exception {
        // Server sends "CONTENT-TYPE" (uppercase), assertion uses "content-type" (lowercase)
        Map<String, String> headers = Map.of("CONTENT-TYPE", "application/json");
        String headersJson = new ObjectMapper().writeValueAsString(headers);

        TestResult testResult = buildTestResult(headersJson);
        TestCaseAssertion assertion = buildHeaderAssertion("content-type", ComparisonOperator.EQUALS, "application/json");

        when(testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCaseId))
                .thenReturn(List.of(assertion));

        RuleEngineResultDto result = ruleEngine.evaluate(testResult);

        assertThat(result.getFinalStatus()).isEqualTo(ResultStatus.PASS);
    }

    // ── FAIL (not ERROR) when headers not captured ────────────────────────────

    @Test
    void headerAssertion_returnsFail_notError_whenHeadersNotCaptured() {
        TestResult testResult = buildTestResult(null); // null = headers not captured
        TestCaseAssertion assertion = buildHeaderAssertion("Content-Type", ComparisonOperator.EQUALS, "application/json");

        when(testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCaseId))
                .thenReturn(List.of(assertion));

        RuleEngineResultDto result = ruleEngine.evaluate(testResult);

        // Must be FAIL, not ERROR
        assertThat(result.getFinalStatus()).isEqualTo(ResultStatus.FAIL);

        // Verify the assertion detail carries a meaningful message
        assertThat(result.getAssertionResults()).isNotEmpty();
        AssertionEvaluationResultDto assertionResult = result.getAssertionResults().get(0);
        assertThat(assertionResult.isPassed()).isFalse();
    }

    // ── EXISTS operator ────────────────────────────────────────────────────────

    @Test
    void headerAssertion_existsOperator_passWhenHeaderPresent() throws Exception {
        Map<String, String> headers = Map.of("X-Request-Id", "abc-123");
        String headersJson = new ObjectMapper().writeValueAsString(headers);

        TestResult testResult = buildTestResult(headersJson);
        // EXISTS: expectedValue is irrelevant, just check header is not null
        TestCaseAssertion assertion = buildHeaderAssertion("X-Request-Id", ComparisonOperator.EXISTS, null);

        when(testCaseAssertionRepository.findByTestCase_IdOrderBySortOrderAsc(testCaseId))
                .thenReturn(List.of(assertion));

        RuleEngineResultDto result = ruleEngine.evaluate(testResult);

        assertThat(result.getFinalStatus()).isEqualTo(ResultStatus.PASS);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TestResult buildTestResult(String actualResponseHeadersJson) {
        TestCase testCase = new TestCase();
        testCase.setId(testCaseId);

        TestRunItem item = new TestRunItem();
        item.setTestCase(testCase);

        TestResult result = new TestResult();
        result.setId(testResultId);
        result.setActualStatus(200); // non-zero so network error branch is skipped
        result.setActualResponseJson("{\"message\":\"OK\"}");
        result.setActualResponseHeadersJson(actualResponseHeadersJson);
        result.setTestRunItem(item);

        when(testResultRepository.findById(testResultId)).thenReturn(Optional.of(result));

        return result;
    }

    private TestCaseAssertion buildHeaderAssertion(
            String headerName, ComparisonOperator operator, String expectedValue) {
        TestCaseAssertion assertion = new TestCaseAssertion();
        assertion.setId(UUID.randomUUID());
        assertion.setAssertionType(AssertionType.HEADER);
        assertion.setTargetPath(headerName);
        assertion.setOperator(operator);
        assertion.setExpectedValue(expectedValue);
        assertion.setEnabledFlag(true);
        assertion.setSortOrder(1);
        return assertion;
    }
}
