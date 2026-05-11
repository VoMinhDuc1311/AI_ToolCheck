package com.aitoolcheck.ai_toolcheck1_backend.service;

/**
 * Common contract for all LLM (Large Language Model) client implementations.
 *
 * <p>Abstracts over different LLM providers (Ollama local, Google Gemini Cloud)
 * allowing the {@code AiModelRouterService} to swap providers transparently.
 *
 * <p>All implementations must:
 * <ul>
 *   <li>Return the raw generated text string.</li>
 *   <li>Apply a Reactor {@code .timeout()} to prevent thread starvation.</li>
 *   <li>Throw a {@link RuntimeException} on failure, which the Router will catch.</li>
 * </ul>
 */
public interface LlmClientService {

    /**
     * Sends a prompt to the underlying LLM and returns the generated text response.
     *
     * @param prompt The complete prompt string to send.
     * @return The raw text response from the model.
     * @throws RuntimeException if the call fails due to timeout, network error, or API error.
     */
    String generateText(String prompt);
}
