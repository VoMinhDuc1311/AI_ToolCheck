package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import reactor.core.publisher.Mono;

public interface GeminiApiClientService {
    /**
     * Gửi prompt tới Google Gemini API và nhận về kết quả (dưới dạng Mono non-blocking).
     *
     * @param promptText Nội dung câu hỏi/yêu cầu muốn gửi.
     * @return Mono chứa chuỗi text phản hồi từ Gemini.
     */
    Mono<String> sendPrompt(String promptText);

    /**
     * Phân tích mã nguồn legacy để trích xuất các API endpoints.
     *
     * @param sourceCode Mã nguồn cần phân tích.
     * @return AiInferenceResultDto chứa kết quả phân tích.
     */
    AiInferenceResultDto extractLegacyApi(String sourceCode);
}
