package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiPayloadOptimizerServiceImpl implements AiPayloadOptimizerService {

    private final ObjectMapper objectMapper;
    private final AiOptimizationProperties properties;

    @Override
    public String truncateIfNeeded(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        if (!properties.isTruncateLargePayload()) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...TRUNCATED_FOR_AI...";
    }

    @Override
    public String safeJsonPreview(String json, int maxChars) {
        if (json == null || json.length() <= maxChars) {
            return json;
        }
        if (!properties.isTruncateLargePayload()) {
            return json;
        }
        try {
            // Attempt to keep validity if it's a JSON array by just returning the array bracket with a message
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray() || node.isObject()) {
                String result = json.substring(0, maxChars - 50) + "...\n\"_TRUNCATED_FOR_AI_\": true";
                // This is a naive truncation for JSON that might break schema, 
                // but it's safe for AI preview if we can't parse deeply.
                return result;
            }
        } catch (Exception e) {
            // Not a valid JSON or parsing failed, fallback to simple string truncate
        }
        return truncateIfNeeded(json, maxChars);
    }

    @Override
    public boolean isTooLarge(String promptOrPayload, int maxChars) {
        return promptOrPayload != null && promptOrPayload.length() > maxChars;
    }

    @Override
    public String compactForAi(String rawContent, int maxChars) {
        if (rawContent == null) return null;
        if (rawContent.length() <= maxChars) return rawContent;
        if (!properties.isTruncateLargePayload()) return rawContent;

        // Strip extra whitespaces, newlines to save space
        String compacted = rawContent.replaceAll("\\s+", " ").trim();
        if (compacted.length() <= maxChars) return compacted;

        return truncateIfNeeded(compacted, maxChars);
    }
}
