package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.service.EmbeddingService;
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
 * Implementation of {@link EmbeddingService} that calls the Ollama {@code /api/embeddings}
 * endpoint to generate dense vector representations of text.
 *
 * <p>Uses Reactor's {@code .timeout()} to avoid blocking the RabbitMQ thread pool
 * indefinitely in case Ollama becomes unresponsive.
 */
@Slf4j
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final WebClient ollamaWebClient;
    private final OllamaProperties ollamaProperties;
    private final ObjectMapper objectMapper;

    public EmbeddingServiceImpl(
            @Qualifier("ollamaWebClient") WebClient ollamaWebClient,
            OllamaProperties ollamaProperties,
            ObjectMapper objectMapper) {
        this.ollamaWebClient = ollamaWebClient;
        this.ollamaProperties = ollamaProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("[EmbeddingService] Input text must not be null or blank.");
        }

        log.info("[EmbeddingService] Bắt đầu embed text — model: {}, độ dài: {} chars",
                ollamaProperties.getEmbedModel(), text.length());

        Map<String, String> payload = Map.of(
                "model", ollamaProperties.getEmbedModel(),
                "prompt", text
        );

        // Call POST /api/embeddings, apply a hard 30s timeout to avoid thread starvation
        String rawResponse = ollamaWebClient.post()
                .uri("/api/embeddings")
                .bodyValue(payload)
                .retrieve()
                .onStatus(status -> status.isError(), resp -> resp.bodyToMono(String.class)
                        .flatMap(err -> Mono.error(new RuntimeException(
                                "[EmbeddingService] Ollama trả về lỗi HTTP: " + err))))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .doOnError(ex -> log.error("[EmbeddingService] Gọi Ollama /api/embeddings thất bại: {}",
                        ex.getMessage()))
                .block();

        return parseEmbeddingFromJson(rawResponse);
    }

    /**
     * Parses the {@code "embedding"} array from the Ollama embeddings response JSON.
     *
     * @param rawJson Raw JSON string from Ollama.
     * @return float array of the embedding vector.
     * @throws RuntimeException if the JSON is malformed or missing the embedding field.
     */
    private float[] parseEmbeddingFromJson(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode embeddingNode = root.path("embedding");

            if (embeddingNode.isMissingNode() || !embeddingNode.isArray()) {
                throw new RuntimeException(
                        "[EmbeddingService] Ollama response thiếu trường 'embedding': " + rawJson);
            }

            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }

            log.info("[EmbeddingService] Embed thành công — vector dimension: {}", vector.length);
            return vector;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("[EmbeddingService] Lỗi parse JSON embedding: " + e.getMessage(), e);
        }
    }
}
