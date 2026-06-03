package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.aitoolcheck.ai_toolcheck1_backend.service.OllamaApiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class OllamaApiClientServiceImpl implements OllamaApiClientService {

    private final WebClient ollamaWebClient;
    private final OllamaProperties ollamaProperties;
    private final ObjectMapper objectMapper;

    public OllamaApiClientServiceImpl(
            @Qualifier("ollamaWebClient") WebClient ollamaWebClient,
            OllamaProperties ollamaProperties,
            ObjectMapper objectMapper) {
        this.ollamaWebClient = ollamaWebClient;
        this.ollamaProperties = ollamaProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void logStartupConfig() {
        log.info("[OllamaClient] Initialized - baseUrl={} primaryModel={} connectTimeoutSeconds={} readTimeoutSeconds={}",
                ollamaProperties.getBaseUrl(),
                ollamaProperties.getPrimaryModel(),
                ollamaProperties.getConnectTimeoutSeconds(),
                ollamaProperties.getReadTimeoutSeconds());
    }

    @Override
    public String generateText(String prompt) {
        return generateTextWithModel(prompt, ollamaProperties.getPrimaryModel());
    }

    @Override
    public String generateTextWithModel(String prompt, String model) {
        return generateTextWithModel(prompt, model, ollamaProperties.getReadTimeoutSeconds());
    }

    @Override
    public String generateTextWithModel(String prompt, String model, int timeoutSeconds) {
        int promptChars = prompt == null ? 0 : prompt.length();
        log.info("[OllamaClient] Calling model: {} - prompt: {} chars", model, promptChars);

        Map<String, Object> payload = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false
        );

        String rawResponse;
        long startedAt = System.nanoTime();
        try {
            rawResponse = ollamaWebClient.post()
                    .uri("/api/generate")
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            resp -> resp.bodyToMono(String.class)
                                    .flatMap(err -> Mono.error(AiProviderFailureException.unavailable(
                                            "Ollama", model, "HTTP " + resp.statusCode() + ": " + err, null))))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds),
                            Mono.error(new TimeoutException(
                                    "[OllamaClient] Timeout after " + timeoutSeconds + "s - model: " + model)))
                    .retryWhen(reactor.util.retry.Retry.backoff(1, Duration.ofSeconds(3))
                            .filter(ex -> !(reactor.core.Exceptions.unwrap(ex) instanceof TimeoutException)
                                    && !(reactor.core.Exceptions.unwrap(ex) instanceof AiProviderFailureException))
                            .doBeforeRetry(s -> log.warn("[OllamaClient] Retry {}/1 - model: {} - reason: {}",
                                    s.totalRetries() + 1, model, s.failure().getMessage())))
                    .doOnError(ex -> log.warn("[OllamaClient] Model {} failed: {}", model, ex.getMessage()))
                    .block();
        } catch (Exception ex) {
            Throwable unwrapped = reactor.core.Exceptions.unwrap(ex);
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            if (unwrapped instanceof AiProviderFailureException providerFailure) {
                throw providerFailure;
            }
            if (unwrapped instanceof TimeoutException || containsTimeoutMessage(unwrapped)) {
                log.warn("[OllamaClient] Timeout model={} timeoutSeconds={} promptChars={} durationMs={}",
                        model, timeoutSeconds, promptChars, durationMs);
                throw AiProviderFailureException.timeout("Ollama", model, timeoutSeconds, promptChars, unwrapped);
            }
            throw AiProviderFailureException.unavailable("Ollama", model, unwrapped.getMessage(), unwrapped);
        }

        if (rawResponse == null || rawResponse.isBlank()) {
            throw AiProviderFailureException.emptyResponse("Ollama", model, null);
        }

        return parseResponseText(rawResponse, model);
    }

    private String parseResponseText(String rawJson, String model) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode responseNode = root.path("response");

            if (responseNode.isMissingNode()) {
                throw AiProviderFailureException.unavailable(
                        "Ollama", model, "response field missing", null);
            }

            String text = responseNode.asText();
            if (text == null || text.isBlank()) {
                throw AiProviderFailureException.emptyResponse("Ollama", model, null);
            }
            log.info("[OllamaClient] Model {} returned {} chars", model, text.length());
            return text;

        } catch (AiProviderFailureException e) {
            throw e;
        } catch (Exception e) {
            throw AiProviderFailureException.unavailable(
                    "Ollama", model, "invalid Ollama response JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            String status = ollamaWebClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(2))
                    .block();
            return status != null && status.contains("Ollama is running");
        } catch (Exception e) {
            log.warn("[OllamaClient] Health check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean containsTimeoutMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null) {
            return false;
        }
        String message = throwable.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("timeout") || message.contains("timed out");
    }
}
