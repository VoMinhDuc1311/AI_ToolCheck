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
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.concurrent.TimeoutException;

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

    @Test
    void readTimeoutException_mapsToLlmTimeout() {
        OllamaProperties properties = new OllamaProperties();
        properties.setReadTimeoutSeconds(90);

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(ReadTimeoutException.INSTANCE))
                .build();
        OllamaApiClientServiceImpl client = new OllamaApiClientServiceImpl(
                webClient, properties, new ObjectMapper());

        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> client.generateTextWithModel("prompt", "qwen2.5-coder:7b", 180));

        assertEquals(AiProviderFailureException.LLM_TIMEOUT, exception.getErrorCode());
        assertEquals(180, exception.getTimeoutSeconds());
        assertTrue(exception.getMessage().contains("timed out after 180s"));
    }

    @Test
    void retryExhausted_preservesTimeoutRootCause() {
        OllamaProperties properties = new OllamaProperties();
        properties.setReadTimeoutSeconds(90);

        WebClient webClient = WebClient.builder()
                // TimeoutException simulates Mono.timeout() firing.
                // The client should catch it and throw LLM_TIMEOUT without wrapping in LLM_PROVIDER_UNAVAILABLE.
                .exchangeFunction(request -> Mono.error(new TimeoutException("Simulated timeout")))
                .build();
        OllamaApiClientServiceImpl client = new OllamaApiClientServiceImpl(
                webClient, properties, new ObjectMapper());

        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> client.generateTextWithModel("prompt", "qwen2.5-coder:7b", 180));

        assertEquals(AiProviderFailureException.LLM_TIMEOUT, exception.getErrorCode());
        assertEquals(180, exception.getTimeoutSeconds());
        assertTrue(exception.getMessage().contains("timed out after 180s"));
    }

    @Test
    void genericOllamaCall_canStillUseDefaultTimeout() {
        OllamaProperties properties = new OllamaProperties();
        properties.setReadTimeoutSeconds(45);
        properties.setPrimaryModel("qwen2.5-coder:7b");

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(ReadTimeoutException.INSTANCE))
                .build();
        OllamaApiClientServiceImpl client = new OllamaApiClientServiceImpl(
                webClient, properties, new ObjectMapper());

        // Calling generateText should use the default primary model and read timeout (45s)
        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> client.generateText("prompt"));

        assertEquals(AiProviderFailureException.LLM_TIMEOUT, exception.getErrorCode());
        assertEquals(45, exception.getTimeoutSeconds());
        assertTrue(exception.getMessage().contains("timed out after 45s"));
    }
}
