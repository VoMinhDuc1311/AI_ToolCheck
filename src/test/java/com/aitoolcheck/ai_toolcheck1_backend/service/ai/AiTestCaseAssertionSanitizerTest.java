package com.aitoolcheck.ai_toolcheck1_backend.service.ai;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiTestCaseAssertionSanitizerTest {

    private final AiTestCaseAssertionSanitizer sanitizer = new AiTestCaseAssertionSanitizer();

    @Test
    void aiGeneratedGreeting_withRootEmptyObjectAssertion_removesBodyAssertion() {
        List<TestCaseAssertion> result = sanitizer.sanitize(
                List.of(
                        status(200),
                        jsonPath("$", ComparisonOperator.EQUALS, "{}")
                ),
                HttpMethod.GET,
                CaseType.POSITIVE,
                200,
                false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAssertionType()).isEqualTo(AssertionType.STATUS_CODE);
        assertThat(result.get(0).getExpectedValue()).isEqualTo("200");
        assertThat(result).noneMatch(a -> a.getAssertionType() == AssertionType.JSON_PATH
                && "$".equals(a.getTargetPath())
                && "{}".equals(a.getExpectedValue()));
    }

    @Test
    void positiveGet_withoutResponseExample_keepsOnlyStatusCode() {
        List<TestCaseAssertion> result = sanitizer.sanitize(
                List.of(
                        status(200),
                        jsonPath("$.content", ComparisonOperator.EXISTS, null),
                        jsonPath("$", ComparisonOperator.EQUALS, "{\"content\":\"Hello\"}")
                ),
                HttpMethod.GET,
                CaseType.POSITIVE,
                200,
                false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAssertionType()).isEqualTo(AssertionType.STATUS_CODE);
        assertThat(result.get(0).getExpectedValue()).isEqualTo("200");
    }

    @Test
    void dynamicIdField_notAssertedAsFixedValue() {
        List<TestCaseAssertion> result = sanitizer.sanitize(
                List.of(
                        status(200),
                        jsonPath("$.id", ComparisonOperator.EQUALS, "1")
                ),
                HttpMethod.POST,
                CaseType.POSITIVE,
                200,
                true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAssertionType()).isEqualTo(AssertionType.STATUS_CODE);
        assertThat(result).noneMatch(a -> "$.id".equals(a.getTargetPath())
                && a.getOperator() == ComparisonOperator.EQUALS);
    }

    @Test
    void sanitizer_neverPersistsZeroAssertions() {
        List<TestCaseAssertion> result = sanitizer.sanitize(
                List.of(jsonPath("$", ComparisonOperator.EQUALS, "{}")),
                HttpMethod.GET,
                CaseType.POSITIVE,
                null,
                false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAssertionType()).isEqualTo(AssertionType.STATUS_CODE);
        assertThat(result.get(0).getExpectedValue()).isEqualTo("200");
    }

    @Test
    void negativeTestCase_notIncorrectlyConvertedToPositive() {
        List<TestCaseAssertion> result = sanitizer.sanitize(
                List.of(
                        status(404),
                        jsonPath("$.error", ComparisonOperator.CONTAINS, "Not Found")
                ),
                HttpMethod.GET,
                CaseType.NEGATIVE,
                null,
                false);

        assertThat(result).extracting(TestCaseAssertion::getExpectedValue)
                .contains("404", "Not Found");
        assertThat(result).noneMatch(a -> a.getAssertionType() == AssertionType.STATUS_CODE
                && "200".equals(a.getExpectedValue()));
    }

    @Test
    void aiGeneratedGreeting_finalAssertions_passExpectedPolicy() {
        List<TestCaseAssertion> result = sanitizer.sanitize(
                List.of(
                        status(200),
                        jsonPath("$", ComparisonOperator.EQUALS, "{}"),
                        jsonPath("$.id", ComparisonOperator.EQUALS, "6")
                ),
                HttpMethod.GET,
                CaseType.POSITIVE,
                200,
                false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAssertionType()).isEqualTo(AssertionType.STATUS_CODE);
        assertThat(result.get(0).getExpectedValue()).isEqualTo("200");
        assertThat(result).noneMatch(a -> a.getAssertionType() == AssertionType.JSON_PATH);
    }

    private TestCaseAssertion status(int expectedStatus) {
        return TestCaseAssertion.builder()
                .assertionType(AssertionType.STATUS_CODE)
                .operator(ComparisonOperator.EQUALS)
                .expectedValue(String.valueOf(expectedStatus))
                .enabledFlag(true)
                .sortOrder(1)
                .build();
    }

    private TestCaseAssertion jsonPath(String path, ComparisonOperator operator, String expectedValue) {
        return TestCaseAssertion.builder()
                .assertionType(AssertionType.JSON_PATH)
                .targetPath(path)
                .operator(operator)
                .expectedValue(expectedValue)
                .enabledFlag(true)
                .sortOrder(2)
                .build();
    }
}
