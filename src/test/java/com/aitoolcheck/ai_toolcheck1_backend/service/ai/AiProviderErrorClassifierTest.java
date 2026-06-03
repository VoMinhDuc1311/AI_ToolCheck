package com.aitoolcheck.ai_toolcheck1_backend.service.ai;

import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
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

        assertEquals(AiProviderFailureException.LLM_TIMEOUT, code);
        assertNotEquals("DTO_VALIDATION_FAILED", code);
    }

    @Test
    void retryExhausted_classifiesAsProviderFailureNotDtoValidation() {
        String code = classifier.classify("Retries exhausted: 4/4");

        assertEquals(AiProviderFailureException.LLM_PROVIDER_UNAVAILABLE, code);
        assertNotEquals("DTO_VALIDATION_FAILED", code);
    }

    @Test
    void quotaText_classifiesAsQuotaExceeded() {
        assertEquals(AiProviderFailureException.LLM_RATE_LIMITED, classifier.classify("RESOURCE_EXHAUSTED quota exceeded"));
    }

    @Test
    void rateLimitText_classifiesAsRateLimited() {
        assertEquals(AiProviderFailureException.LLM_RATE_LIMITED, classifier.classify("429 too many requests rate limit"));
    }

    @Test
    void invalidJsonResponse_classifiesAsInvalidResponse() {
        assertEquals("AI_RESPONSE_INVALID", classifier.classify("Malformed JSON after extraction"));
    }
}
