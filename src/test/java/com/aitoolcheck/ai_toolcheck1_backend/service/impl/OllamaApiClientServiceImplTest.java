package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaApiClientServiceImplTest {

    @Test
    void generateTextWithModel_whenRequestTimesOutThrowsTypedTimeout() {
        OllamaProperties properties = new OllamaProperties();
        properties.setReadTimeoutSeconds(1);

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.never())
                .build();
        OllamaApiClientServiceImpl client = new OllamaApiClientServiceImpl(
                webClient, properties, new ObjectMapper());

        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> client.generateTextWithModel("prompt", "qwen2.5-coder:7b"));

        assertEquals(AiProviderFailureException.LLM_TIMEOUT, exception.getErrorCode());
        assertEquals(1, exception.getTimeoutSeconds());
        assertTrue(exception.getMessage().contains("qwen2.5-coder:7b"));
    }
}
