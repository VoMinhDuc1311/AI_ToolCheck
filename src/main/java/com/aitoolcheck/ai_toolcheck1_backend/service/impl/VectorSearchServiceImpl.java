package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.service.EmbeddingService;
import com.aitoolcheck.ai_toolcheck1_backend.service.VectorSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link VectorSearchService} using pgvector's cosine distance operator ({@code <=>}).
 *
 * <p>Uses a dedicated {@link JdbcTemplate} backed by PostgreSQL (not the primary MySQL datasource)
 * to execute native vector similarity queries.
 *
 * <p>All failures are caught and logged gracefully — the RAG layer must never crash the AI pipeline.
 * If vector search fails, the method returns an empty context string, allowing the LLM to work
 * without RAG context rather than failing the entire job.
 */
@Slf4j
@Service
public class VectorSearchServiceImpl implements VectorSearchService {

    private final EmbeddingService embeddingService;
    private final JdbcTemplate vectorJdbcTemplate;

    public VectorSearchServiceImpl(
            EmbeddingService embeddingService,
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate) {
        this.embeddingService = embeddingService;
        this.vectorJdbcTemplate = vectorJdbcTemplate;
    }

    @Override
    public String findSimilarContext(String queryText, int topK) {
        log.info("[VectorSearch] Bắt đầu tìm kiếm context RAG — topK: {}", topK);

        try {
            // Step 1: Embed the query text
            float[] queryVector = embeddingService.embed(queryText);
            String vectorStr = toPostgresVectorString(queryVector);

            // Step 2: Query pgvector with cosine distance operator <=>
            // Casts the string literal to the vector type for correct operator resolution
            String sql = """
                    SELECT content
                    FROM api_vector_store
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """;

            List<String> results = vectorJdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> rs.getString("content"),
                    vectorStr, topK
            );

            if (results.isEmpty()) {
                log.info("[VectorSearch] Không tìm thấy context liên quan trong Vector Store.");
                return "";
            }

            // Step 3: Merge the top-K results into a single context block
            String mergedContext = results.stream()
                    .map(content -> "--- RELEVANT EXAMPLE ---\n" + content)
                    .collect(Collectors.joining("\n\n"));

            log.info("[VectorSearch] Tìm thấy {} tài liệu liên quan. Tổng context: {} chars",
                    results.size(), mergedContext.length());

            return mergedContext;

        } catch (Exception e) {
            // CRITICAL: RAG failures must NOT propagate — the pipeline continues without context
            log.warn("[VectorSearch] Tìm kiếm Vector thất bại, tiếp tục không có RAG context: {}",
                    e.getMessage());
            return "";
        }
    }

    @Override
    public void storeEmbedding(String sourceId, String content) {
        log.info("[VectorSearch] Lưu embedding mới — sourceId: {}, độ dài content: {} chars",
                sourceId, content != null ? content.length() : 0);

        try {
            if (content == null || content.isBlank()) {
                log.warn("[VectorSearch] Bỏ qua storeEmbedding — content rỗng.");
                return;
            }

            // Step 1: Generate embedding
            float[] vector = embeddingService.embed(content);
            String vectorStr = toPostgresVectorString(vector);

            // Step 2: Insert into pgvector table
            String sql = """
                    INSERT INTO api_vector_store (source_id, content, embedding)
                    VALUES (?, ?, ?::vector)
                    """;

            vectorJdbcTemplate.update(sql, sourceId, content, vectorStr);

            log.info("[VectorSearch] Đã lưu embedding thành công — sourceId: {}", sourceId);

        } catch (Exception e) {
            // CRITICAL: Storing failure must NOT crash the pipeline
            log.error("[VectorSearch] Lưu embedding thất bại — sourceId: {}, lý do: {}",
                    sourceId, e.getMessage());
        }
    }

    /**
     * Converts a Java float array to the PostgreSQL vector literal format.
     *
     * <p>Example: [0.1, -0.2, 0.3] → "[0.1,-0.2,0.3]"
     * The output is compatible with pgvector's {@code ::vector} cast.
     *
     * @param vector The embedding float array.
     * @return A string in pgvector literal format.
     */
    private String toPostgresVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
