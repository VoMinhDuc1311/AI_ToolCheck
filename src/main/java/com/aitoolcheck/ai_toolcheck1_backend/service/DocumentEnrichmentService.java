package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiDocumentEnrichmentResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse;

public interface DocumentEnrichmentService {

    /**
     * Gọi AI Skill 1 để làm giàu tài liệu (Enrich Document).
     * 
     * @param openApiFragment Đoạn metadata JSON mô tả API Endpoint (từ Dev A).
     * @return DTO chứa summary, description và mock JSON đã được parse an toàn.
     */
    AiDocumentEnrichmentResponseDto enrichDocumentation(String apiEndpointId, String openApiFragment);

    /**
     * Hàm phụ trợ lấy toàn bộ Response từ Gemini để Consumer có thể lấy
     * UsageMetadata (Token).
     * 
     * @param openApiFragment Đoạn metadata JSON.
     * @return GeminiResponse chứa cả Text và Token Usage.
     */
    GeminiResponse getRawGeminiResponse(String openApiFragment);
}