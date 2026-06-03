package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiApiClientServiceImplTest {

    @Test
    void buildRateLimitException_parsesRetryDelayFromGoogleRetryInfo() {
        GeminiProperties properties = new GeminiProperties();
        properties.setModel("gemini-2.5-flash");

        GeminiApiClientServiceImpl client = new GeminiApiClientServiceImpl(
                WebClient.builder().baseUrl("http://localhost").build(),
                properties,
                new ObjectMapper());

        String body = """
                {
                  "error": {
                    "code": 429,
                    "status": "RESOURCE_EXHAUSTED",
                    "details": [
                      {
                        "@type": "type.googleapis.com/google.rpc.RetryInfo",
                        "retryDelay": "25s"
                      }
                    ]
                  }
                }
                """;

        AiProviderFailureException exception = client.buildRateLimitException(body);

        assertEquals(AiProviderFailureException.LLM_RATE_LIMITED, exception.getErrorCode());
        assertEquals(25, exception.getRetryAfterSeconds());
        assertTrue(exception.getMessage().contains("Gemini"));
        assertTrue(exception.getMessage().contains("quota/rate limit"));
    }
}
