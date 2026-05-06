package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.service.OllamaApiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Implementation of {@link OllamaApiClientService} that calls the Ollama
 * {@code /api/generate} endpoint in non-streaming mode.
 *
 * <p>All HTTP calls apply a Reactor {@code .timeout()} aligned with
 * {@link OllamaProperties#getReadTimeoutSeconds()} to prevent RabbitMQ
 * consumer threads from being blocked indefinitely.
 *
 * <p>Payload format (Ollama /api/generate):
 * <pre>{@code
 * {
 *   "model": "qwen3-coder:30b",
 *   "prompt": "...",
 *   "stream": false
 * }
 * }</pre>
 *
 * <p>Response format:
 * <pre>{@code
 * {
 *   "response": "generated text here",
 *   "done": true
 * }
 * }</pre>
 */
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

    /**
     * Convenience method: generates text using the configured primary Ollama model.
     * Delegates to {@link #generateTextWithModel(String, String)}.
     *
     * @param prompt The prompt to send.
     * @return Raw generated text from Ollama.
     */
    @Override
    public String generateText(String prompt) {
        return generateTextWithModel(prompt, ollamaProperties.getPrimaryModel());
    }

    /**
     * Sends a prompt to Ollama using the specified model.
     *
     * @param prompt The full prompt string.
     * @param model  The Ollama model identifier (e.g., "qwen3-coder:30b").
     * @return Raw generated text from Ollama.
     * @throws RuntimeException if Ollama returns an error or times out.
     */
    @Override
    public String generateTextWithModel(String prompt, String model) {
        log.info("[OllamaClient] Gọi model: {} — độ dài prompt: {} chars", model, prompt.length());

        Map<String, Object> payload = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false
        );

        String rawResponse = ollamaWebClient.post()
                .uri("/api/generate")
                .bodyValue(payload)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class)
                                .flatMap(err -> Mono.error(new RuntimeException(
                                        "[OllamaClient] Ollama HTTP Error [model=" + model + "]: " + err))))
                .bodyToMono(String.class)
                // Apply reactive timeout to avoid blocking RabbitMQ threads
                .timeout(Duration.ofSeconds(ollamaProperties.getReadTimeoutSeconds()),
                        Mono.error(new RuntimeException(
                                "[OllamaClient] Timeout sau " + ollamaProperties.getReadTimeoutSeconds()
                                        + "s — model: " + model)))
                .doOnError(ex -> log.warn("[OllamaClient] Gọi model {} thất bại: {}", model, ex.getMessage()))
                .block();

        return parseResponseText(rawResponse, model);
    }

    /**
     * Extracts the {@code "response"} field from the Ollama generate response JSON.
     *
     * @param rawJson The raw JSON string from Ollama.
     * @param model   Model name for logging context.
     * @return The extracted generated text.
     * @throws RuntimeException if the JSON is malformed or missing the response field.
     */
    private String parseResponseText(String rawJson, String model) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode responseNode = root.path("response");

            if (responseNode.isMissingNode()) {
                throw new RuntimeException(
                        "[OllamaClient] Phản hồi từ model '" + model + "' thiếu trường 'response'. Raw: " + rawJson);
            }

            String text = responseNode.asText();
            log.info("[OllamaClient] Model {} phản hồi thành công — {} chars", model, text.length());
            return text;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("[OllamaClient] Lỗi parse JSON phản hồi từ Ollama: " + e.getMessage(), e);
        }
    }
}
