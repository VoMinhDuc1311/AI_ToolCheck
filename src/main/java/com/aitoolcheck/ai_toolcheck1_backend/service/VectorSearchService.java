package com.aitoolcheck.ai_toolcheck1_backend.service;

/**
 * Service for performing semantic similarity searches against the pgvector store.
 *
 * <p>Implements the Retrieval (R) part of Retrieval-Augmented Generation (RAG).
 * Given a query text, it embeds the query and finds the most semantically
 * similar documents previously stored, then returns them as a combined context string.
 */
public interface VectorSearchService {

    /**
     * Finds the {@code topK} most semantically similar documents to the given query text
     * and returns them as a single merged context string for use in LLM prompts.
     *
     * <p>If the vector store is empty or Ollama/pgvector is unavailable,
     * returns an empty string gracefully without throwing.
     *
     * @param queryText The text to find similar documents for.
     * @param topK      The maximum number of similar documents to retrieve.
     * @return A concatenated string of the top-K relevant document contents,
     *         or an empty string if no similar documents are found.
     */
    String findSimilarContext(String queryText, int topK);

    /**
     * Embeds the given content and stores the resulting vector in pgvector
     * for future retrieval.
     *
     * <p>Called after a successful AI enrichment to grow the knowledge base.
     *
     * @param sourceId Optional identifier (e.g., ApiEndpoint ID) for traceability.
     * @param content  The text content to embed and store.
     */
    void storeEmbedding(String sourceId, String content);
}
