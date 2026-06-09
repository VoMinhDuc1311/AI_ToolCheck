package com.aitoolcheck.ai_toolcheck1_backend.service.ai;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class AiTestCaseAssertionSanitizer {

    private static final Set<HttpMethod> SAFE_READ_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private static final Set<String> DYNAMIC_FIELD_NAMES = Set.of(
            "id", "uuid", "createdat", "updatedat", "timestamp", "date", "token", "random", "version");

    public List<TestCaseAssertion> sanitize(
            List<TestCaseAssertion> assertions,
            HttpMethod httpMethod,
            CaseType caseType,
            Integer expectedStatusCode,
            boolean hasExplicitStableResponseExample) {

        List<TestCaseAssertion> result = new ArrayList<>();
        boolean positiveSafeRead = isPositiveSafeRead(httpMethod, caseType);
        int fallbackStatus = resolveFallbackStatus(assertions, caseType, expectedStatusCode);

        if (assertions != null) {
            for (TestCaseAssertion assertion : assertions) {
                if (assertion == null || Boolean.FALSE.equals(assertion.getEnabledFlag())) {
                    continue;
                }
                if (shouldRemove(assertion, positiveSafeRead, hasExplicitStableResponseExample)) {
                    continue;
                }
                result.add(assertion);
            }
        }

        if (positiveSafeRead && !hasStatusCodeAssertion(result)) {
            result.add(buildStatusCodeAssertion(fallbackStatus));
        }

        if (result.isEmpty()) {
            result.add(buildStatusCodeAssertion(fallbackStatus));
        }

        resequence(result);
        return result;
    }

    private boolean shouldRemove(
            TestCaseAssertion assertion,
            boolean positiveSafeRead,
            boolean hasExplicitStableResponseExample) {

        if (assertion.getAssertionType() == AssertionType.STATUS_CODE) {
            return false;
        }

        if (positiveSafeRead && !hasExplicitStableResponseExample) {
            return true;
        }

        if (isExactWholeBodyEquality(assertion) && !hasExplicitStableResponseExample) {
            return true;
        }

        return isFixedEqualityOnDynamicField(assertion);
    }

    private boolean isExactWholeBodyEquality(TestCaseAssertion assertion) {
        if (assertion.getOperator() != ComparisonOperator.EQUALS) {
            return false;
        }

        AssertionType type = assertion.getAssertionType();
        String expected = normalize(assertion.getExpectedValue());
        String path = normalizePath(assertion.getTargetPath());

        boolean emptyWholeBodyExpected = "{}".equals(expected) || "[]".equals(expected);
        boolean rootPath = path.isEmpty() || "$".equals(path);

        if (type == AssertionType.JSON_PATH && rootPath && emptyWholeBodyExpected) {
            return true;
        }

        return (type == AssertionType.BODY_CONTAINS || type == AssertionType.BODY_NOT_NULL)
                && emptyWholeBodyExpected;
    }

    private boolean isFixedEqualityOnDynamicField(TestCaseAssertion assertion) {
        if (assertion.getAssertionType() != AssertionType.JSON_PATH
                || assertion.getOperator() != ComparisonOperator.EQUALS) {
            return false;
        }

        String path = normalizePath(assertion.getTargetPath());
        if (path.isEmpty() || "$".equals(path)) {
            return false;
        }

        String lastSegment = path;
        int dot = lastSegment.lastIndexOf('.');
        if (dot >= 0 && dot < lastSegment.length() - 1) {
            lastSegment = lastSegment.substring(dot + 1);
        }
        int bracket = lastSegment.lastIndexOf('[');
        if (bracket >= 0 && bracket < lastSegment.length() - 1) {
            lastSegment = lastSegment.substring(bracket + 1);
        }
        lastSegment = lastSegment.replace("]", "").replace("'", "").replace("\"", "");

        return DYNAMIC_FIELD_NAMES.contains(lastSegment.toLowerCase(Locale.ROOT));
    }

    private boolean isPositiveSafeRead(HttpMethod method, CaseType caseType) {
        return caseType == CaseType.POSITIVE && method != null && SAFE_READ_METHODS.contains(method);
    }

    private int resolveFallbackStatus(List<TestCaseAssertion> assertions, CaseType caseType, Integer expectedStatusCode) {
        if (expectedStatusCode != null && expectedStatusCode >= 100 && expectedStatusCode <= 599) {
            return expectedStatusCode;
        }
        Integer existingStatus = extractExistingStatus(assertions);
        if (existingStatus != null) {
            return existingStatus;
        }
        if (caseType == CaseType.VALIDATION) {
            return 400;
        }
        if (caseType == CaseType.AUTHORIZATION || caseType == CaseType.AUTHENTICATION) {
            return 401;
        }
        if (caseType == CaseType.NEGATIVE) {
            return 500;
        }
        return 200;
    }

    private Integer extractExistingStatus(List<TestCaseAssertion> assertions) {
        if (assertions == null) {
            return null;
        }
        for (TestCaseAssertion assertion : assertions) {
            if (assertion == null || assertion.getAssertionType() != AssertionType.STATUS_CODE) {
                continue;
            }
            String expected = assertion.getExpectedValue();
            if (expected == null) {
                continue;
            }
            try {
                int status = Integer.parseInt(expected.trim());
                if (status >= 100 && status <= 599) {
                    return status;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private boolean hasStatusCodeAssertion(List<TestCaseAssertion> assertions) {
        return assertions.stream().anyMatch(a -> a.getAssertionType() == AssertionType.STATUS_CODE);
    }

    private TestCaseAssertion buildStatusCodeAssertion(int statusCode) {
        return TestCaseAssertion.builder()
                .assertionType(AssertionType.STATUS_CODE)
                .targetPath(null)
                .operator(ComparisonOperator.EQUALS)
                .expectedValue(String.valueOf(statusCode))
                .enabledFlag(true)
                .sortOrder(1)
                .build();
    }

    private void resequence(List<TestCaseAssertion> assertions) {
        for (int i = 0; i < assertions.size(); i++) {
            assertions.get(i).setSortOrder(i + 1);
            if (assertions.get(i).getEnabledFlag() == null) {
                assertions.get(i).setEnabledFlag(true);
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.trim();
    }
}
