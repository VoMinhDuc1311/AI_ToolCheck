package com.aitoolcheck.ai_toolcheck1_backend.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AiProviderErrorClassifierTest {

    private AiProviderErrorClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new AiProviderErrorClassifier();
    }

    @Test
    void timeoutException_classifiesAsTimeoutNotDtoValidation() {
        String code = classifier.classify(new RuntimeException(new TimeoutException("Ollama timeout after 90s")));

        assertEquals("AI_TIMEOUT", code);
        assertNotEquals("DTO_VALIDATION_FAILED", code);
    }

    @Test
    void retryExhausted_classifiesAsProviderFailureNotDtoValidation() {
        String code = classifier.classify("Retries exhausted: 4/4");

        assertEquals("AI_PROVIDER_FAILED", code);
        assertNotEquals("DTO_VALIDATION_FAILED", code);
    }

    @Test
    void quotaText_classifiesAsQuotaExceeded() {
        assertEquals("AI_QUOTA_EXCEEDED", classifier.classify("RESOURCE_EXHAUSTED quota exceeded"));
    }

    @Test
    void rateLimitText_classifiesAsRateLimited() {
        assertEquals("AI_RATE_LIMITED", classifier.classify("429 too many requests rate limit"));
    }

    @Test
    void invalidJsonResponse_classifiesAsInvalidResponse() {
        assertEquals("AI_RESPONSE_INVALID", classifier.classify("Malformed JSON after extraction"));
    }
}
