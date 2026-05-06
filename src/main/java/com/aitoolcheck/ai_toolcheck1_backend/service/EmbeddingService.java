package com.aitoolcheck.ai_toolcheck1_backend.service;

/**
 * Service for converting text into vector embeddings via the Ollama Embedding API.
 *
 * <p>Uses the {@code mxbai-embed-large} model which produces 1024-dimensional
 * float vectors suitable for semantic similarity search in pgvector.
 */
public interface EmbeddingService {

    /**
     * Converts the given text into a dense float vector embedding.
     *
     * <p>Calls the Ollama {@code /api/embeddings} endpoint with the configured
     * embedding model. The returned array length is model-dependent
     * (1024 for mxbai-embed-large).
     *
     * @param text The input text to embed. Must not be null or blank.
     * @return A float array representing the semantic embedding of the input.
     * @throws RuntimeException if Ollama is unreachable or returns an error.
     */
    float[] embed(String text);
}
