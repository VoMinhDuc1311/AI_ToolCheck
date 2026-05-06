package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration Properties for Ollama Local LLM.
 * Maps properties from the {@code ai.ollama} section in application.yaml.
 *
 * <p>
 * Ollama is used as the primary LLM provider.
 * Google Gemini Cloud is the last-resort fallback.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.ollama")
public class OllamaProperties {

    /**
     * Base URL of the local Ollama server.
     * Default: http://localhost:11434
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * Primary high-performance model (Tier 1).
     * SỬA DÒNG NÀY: Đổi từ qwen3-coder:30b thành qwen2.5-coder:7b
     */
    private String primaryModel = "qwen2.5-coder:7b";

    /**
     * Fallback lightweight model (Tier 2).
     * Default: qwen2.5-coder:7b
     */
    private String fallbackModel = "qwen2.5-coder:7b";

    /**
     * Embedding model for RAG pipeline.
     * Default: mxbai-embed-large (1024-dimensional output)
     */
    private String embedModel = "mxbai-embed-large";

    /**
     * TCP connection timeout to Ollama in seconds.
     */
    private int connectTimeoutSeconds = 10;

    /**
     * HTTP read timeout for Ollama generation in seconds.
     * Large models (30B) may take up to 2 minutes for complex prompts.
     */
    private int readTimeoutSeconds = 120;
}