package com.aitoolcheck.ai_toolcheck1_backend.service;

/**
 * Extended LLM client interface for Ollama Local LLM.
 *
 * <p>Extends {@link LlmClientService} with the ability to specify which
 * Ollama model to use per call. This allows the {@code AiModelRouterService}
 * to dynamically switch between {@code qwen3-coder:30b} (primary) and
 * {@code qwen2.5-coder:7b} (fallback) using the same client bean.
 */
public interface OllamaApiClientService extends LlmClientService {

    /**
     * Sends a prompt to Ollama using the specified model and returns the generated text.
     *
     * <p>This method is the primary entry point for the Router tier logic.
     * The model name must be a valid model that has been pulled into Ollama
     * (e.g., via {@code ollama pull qwen3-coder:30b}).
     *
     * @param prompt The complete prompt string.
     * @param model  The Ollama model name to use (e.g., "qwen3-coder:30b").
     * @return The raw text response from the model.
     * @throws RuntimeException on timeout, network failure, or Ollama API error.
     */
    String generateTextWithModel(String prompt, String model);

    /**
     * Sends a prompt to Ollama using a per-call timeout.
     *
     * @param prompt The complete prompt string.
     * @param model The Ollama model name.
     * @param timeoutSeconds Timeout for this generation call.
     * @return The raw text response from the model.
     */
    String generateTextWithModel(String prompt, String model, int timeoutSeconds);

    /**
     * Check if Ollama is healthy and reachable.
     */
    boolean isHealthy();
}
