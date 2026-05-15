package com.aitoolcheck.ai_toolcheck1_backend.service;

public interface AiPayloadOptimizerService {
    String truncateIfNeeded(String text, int maxChars);
    String safeJsonPreview(String json, int maxChars);
    boolean isTooLarge(String promptOrPayload, int maxChars);
    String compactForAi(String rawContent, int maxChars);
}
