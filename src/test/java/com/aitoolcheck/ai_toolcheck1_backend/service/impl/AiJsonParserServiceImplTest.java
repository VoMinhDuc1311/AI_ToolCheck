package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiJsonParserServiceImplTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final AiJsonParserServiceImpl parser = new AiJsonParserServiceImpl(
            new ObjectMapper(),
            validator,
            new SimpleMeterRegistry());

    // ── Existing sanitization tests ──────────────────────────────────────────

    @Test
    void extractAndSanitizeJson_whenRawResponseIsNullThrowsEmptyResponseNotInvalidJson() {
        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> parser.extractAndSanitizeJson(null));

        assertEquals(AiProviderFailureException.LLM_EMPTY_RESPONSE, exception.getErrorCode());
    }

    @Test
    void extractAndSanitizeJson_whenMalformedRawTextExistsThrowsInvalidJsonSyntax() {
        AiJsonParseException exception = assertThrows(
                AiJsonParseException.class,
                () -> parser.extractAndSanitizeJson("{not valid json}"));

        assertEquals(AiJsonParseException.ErrorType.INVALID_JSON_SYNTAX, exception.getErrorType());
    }

    @Test
    void extractAndSanitizeJson_whenFencedJsonExtractsSuccessfully() {
        String normalized = parser.extractAndSanitizeJson("""
                ```json
                {"summary":"ok"}
                ```
                """);

        assertEquals("{\"summary\":\"ok\"}", normalized);
    }

    // ── Existing alias/wrapper tests ─────────────────────────────────────────

    @Test
    void cleanAndParseTestCaseJson_withWrapperAndAliases() {
        String rawJson = """
                {
                  "testCases": [
                    {
                      "test_name": "Get User Profile",
                      "case_type": "SUCCESS",
                      "priority_level": "HIGH",
                      "assertions": [
                        {
                          "assertion_type": "STATUS_CODE",
                          "expected_value": "200"
                        }
                      ]
                    }
                  ]
                }
                """;
        var results = parser.cleanAndParseTestCaseJson(rawJson);
        assertEquals(1, results.size());
        assertEquals("Get User Profile", results.get(0).getCaseName());
        assertEquals("POSITIVE", results.get(0).getCaseType());
    }

    @Test
    void cleanAndParseTestCaseJson_withCasesWrapper() {
        String rawJson = """
                {
                  "cases": [
                    {
                      "testName": "Get User Info",
                      "case_type": "SUCCESS",
                      "priority_level": "HIGH",
                      "assertions": []
                    }
                  ]
                }
                """;
        var results = parser.cleanAndParseTestCaseJson(rawJson);
        assertEquals(1, results.size());
        assertEquals("Get User Info", results.get(0).getCaseName());
    }

    @Test
    void cleanAndParseTestCaseJson_withBareArrayAndAliases() {
        String rawJson = """
                [
                  {
                    "test_name": "Auth Failure",
                    "case_type": "CLIENT_ERROR",
                    "priority_level": "MEDIUM",
                    "assertions": [
                      {
                        "assertion_type": "JSON_BODY",
                        "expected_value": "Unauthorized"
                      }
                    ]
                  }
                ]
                """;
        var results = parser.cleanAndParseTestCaseJson(rawJson);
        assertEquals(1, results.size());
        assertEquals("Auth Failure", results.get(0).getCaseName());
        assertEquals("VALIDATION", results.get(0).getCaseType());
    }

    // ── Regression: FAILURE alias ────────────────────────────────────────────

    /**
     * AI emits {@code case_type: "FAILURE"} — must be mapped to {@code "NEGATIVE"}.
     * This was not covered before; regression guard against reverting the fix.
     */
    @Test
    void cleanAndParseTestCaseJson_failureAliasMapsToNegative() {
        String rawJson = """
                {
                  "testCases": [
                    {
                      "case_name": "Server Error Scenario",
                      "case_type": "FAILURE",
                      "priority_level": "HIGH",
                      "assertions": [
                        {
                          "assertion_type": "STATUS_CODE",
                          "expected_value": "500"
                        }
                      ]
                    }
                  ]
                }
                """;
        var results = parser.cleanAndParseTestCaseJson(rawJson);
        assertEquals(1, results.size());
        assertEquals("NEGATIVE", results.get(0).getCaseType());
    }

    /**
     * SUCCESS/FAILURE appearing in free-text fields (case_name, expectedValue, description)
     * must NOT be mutated by the alias normalizer — only the {@code case_type} field is mapped.
     *
     * <p>This is the regression test for the scoped-replace fix introduced in Phase 0.
     * Previously {@code .replace("\"SUCCESS\"", "\"POSITIVE\"")} would corrupt any JSON
     * string value that happened to contain these words.</p>
     */
    @Test
    void cleanAndParseTestCaseJson_enumAliasDoesNotMutateOtherStringFields() {
        String rawJson = """
                {
                  "testCases": [
                    {
                      "case_name": "Payment SUCCESS confirmed",
                      "case_type": "SUCCESS",
                      "priority_level": "MEDIUM",
                      "assertions": [
                        {
                          "assertion_type": "STATUS_CODE",
                          "expected_value": "200"
                        }
                      ]
                    },
                    {
                      "case_name": "Payment FAILURE report",
                      "case_type": "FAILURE",
                      "priority_level": "HIGH",
                      "assertions": [
                        {
                          "assertion_type": "STATUS_CODE",
                          "expected_value": "500"
                        }
                      ]
                    }
                  ]
                }
                """;
        var results = parser.cleanAndParseTestCaseJson(rawJson);
        assertEquals(2, results.size());

        // case_type aliases are mapped correctly
        assertEquals("POSITIVE", results.get(0).getCaseType());
        assertEquals("NEGATIVE", results.get(1).getCaseType());

        // Free-text case_name values must NOT be mutated
        assertEquals("Payment SUCCESS confirmed", results.get(0).getCaseName());
        assertEquals("Payment FAILURE report",    results.get(1).getCaseName());
    }

    @Test
    void parseTestCaseRequest_whenExpectedValueIsJsonObject_normalizesToString() {
        String rawJson = """
                ```json
                {
                  "test_cases": [
                    {
                      "test_name": "Get User Profile",
                      "case_type": "SUCCESS",
                      "priority": "HIGH",
                      "http_method": "GET",
                      "url": "/api/users/profile",
                      "assertions": [
                        {
                          "assertion_type": "JSON_PATH",
                          "json_path": "$.user",
                          "comparison_operator": "EQUALS",
                          "expected_value": {
                            "code": "USER_NOT_FOUND",
                            "message": "User not found"
                          }
                        }
                      ]
                    }
                  ]
                }
                ```
                """;
        var request = parser.parseTestCaseRequest(rawJson);
        assertEquals(1, request.getTestCases().size());
        var tc = request.getTestCases().get(0);
        assertEquals("Get User Profile", tc.getTestName());
        assertEquals("HIGH", tc.getPriority());
        assertEquals(1, tc.getAssertions().size());
        var assertion = tc.getAssertions().get(0);
        assertEquals("{\"code\":\"USER_NOT_FOUND\",\"message\":\"User not found\"}", assertion.getExpectedValue());
    }

    @Test
    void parseTestCaseRequest_whenExpectedValueIsJsonArray_normalizesToString() {
        String rawJson = """
                [
                  {
                    "case_name": "Get Items",
                    "case_type": "SUCCESS",
                    "priority_level": "MEDIUM",
                    "http_method": "GET",
                    "url": "/api/items",
                    "assertions": [
                      {
                        "assertion_type": "JSON_PATH",
                        "json_path": "$.ids",
                        "comparison_operator": "EQUALS",
                        "expected_value": [1, 2, 3]
                      }
                    ]
                  }
                ]
                """;
        var request = parser.parseTestCaseRequest(rawJson);
        assertEquals(1, request.getTestCases().size());
        var tc = request.getTestCases().get(0);
        assertEquals("Get Items", tc.getTestName());
        assertEquals(1, tc.getAssertions().size());
        var assertion = tc.getAssertions().get(0);
        assertEquals("[1,2,3]", assertion.getExpectedValue());
    }
}

