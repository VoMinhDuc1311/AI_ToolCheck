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
     * Gọi Gemini và trả về toàn bộ đối tượng GeminiResponse đã được parse, 
     * chứa cả usageMetadata (Token tracking) và content.
     *
     * @param sourceCode Mã nguồn Java cần phân tích.
     * @return Đối tượng GeminiResponse chứa metadata và chuỗi text.
     */
    com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse getFullAiResponse(String sourceCode);

    /**
     * Gọi Gemini với Skill 0 (Legacy Extractor) và trả về raw String chưa parse.
     * <p>
     * Đây là method được thiết kế để Consumer gọi — trả về text thô từ AI
     * để sau đó đưa qua {@code AiJsonParserService} làm sạch và parse.
     * Tách bạch hoàn toàn trách nhiệm: Gemini Client chỉ lo gọi API,
     * không tự parse JSON.
     * </p>
     *
     * @param sourceCode Mã nguồn Java cần phân tích.
     * @return Chuỗi JSON thô từ Gemini (có thể còn chứa markdown hoặc text dư thừa).
     */
    String getRawAiResponse(String sourceCode);

    /**
     * Phân tích mã nguồn legacy để trích xuất các API endpoints.
     * <p>
     * @deprecated Dùng {@link #getRawAiResponse(String)} thay thế để tách trách nhiệm parse.
     * Giữ lại để tránh breaking change với các caller cũ.
     * </p>
     *
     * @param sourceCode Mã nguồn cần phân tích.
     * @return AiInferenceResultDto chứa kết quả phân tích.
     */
    @Deprecated(since = "Task3", forRemoval = false)
    AiInferenceResultDto extractLegacyApi(String sourceCode);
}
