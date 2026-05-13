package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException.ErrorType;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tổng đài viên (Intelligent LLM Router) cho toàn bộ hệ thống AI.
 *
 * <p>
 * Điều phối các lời gọi LLM theo chiến lược ưu tiên 3 tầng (Tiered Fallback):
 * <ol>
 * <li><b>Tier 1 — Primary (Local):</b> Ollama với model {@code qwen3-coder:30b}
 * (chất lượng cao nhất).</li>
 * <li><b>Tier 2 — Fallback (Local):</b> Ollama với model
 * {@code qwen2.5-coder:7b} (nhanh hơn, nhẹ hơn).</li>
 * <li><b>Tier 3 — Last Resort (Cloud):</b> Google Gemini API. Có thêm 15s
 * throttle để tránh Rate Limit 429.</li>
 * </ol>
 *
 * <p>
 * Nếu tất cả 3 tier đều thất bại, ném ra {@link AiJsonParseException} với
 * {@link ErrorType#INVALID_JSON_SYNTAX} để Consumer đánh dấu Job là FAILED
 * và lưu Audit Log.
 *
 * <p>
 * <b>Thiết kế quan trọng:</b> Không có {@code Thread.sleep} cho Ollama Local.
 * Sleep 15s chỉ được áp dụng trước khi gọi Gemini Cloud (Tier 3) để tuân thủ
 * Rate Limit của Google Free Tier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelRouterService {

    private final OllamaApiClientService ollamaApiClientService;
    private final GeminiApiClientService geminiApiClientService;
    private final OllamaProperties ollamaProperties;
    private final AiJobLogService aiJobLogService;

    /**
     * Thực thi prompt qua chuỗi fallback 3 tầng và trả về raw text từ LLM đầu tiên
     * thành công.
     *
     * @param prompt Prompt hoàn chỉnh (đã bao gồm cả RAG context nếu có).
     * @return Raw text phản hồi từ LLM.
     * @throws AiJsonParseException nếu tất cả 3 provider đều thất bại.
     */
    public String executeWithFallback(String prompt) {

        // ─── TIER 1: Ollama Primary — qwen3-coder:30b ────────────────────────
        try {
            log.info("[Router][Tier1] Đang gọi Ollama model: {} — prompt: {} chars",
                    ollamaProperties.getPrimaryModel(), prompt.length());

            String result = ollamaApiClientService.generateTextWithModel(
                    prompt, ollamaProperties.getPrimaryModel());

            log.info("[Router][Tier1] Thành công với model: {}", ollamaProperties.getPrimaryModel());
            return result;

        } catch (Exception tier1Ex) {
            log.warn("[Router][Tier1] Model {} thất bại: {}. Chuyển sang Tier 2...",
                    ollamaProperties.getPrimaryModel(), tier1Ex.getMessage());
        }

        // ─── TIER 2: Ollama Fallback — qwen2.5-coder:7b ─────────────────────
        try {
            log.info("[Router][Tier2] Fallback sang Ollama model: {}",
                    ollamaProperties.getFallbackModel());

            String result = ollamaApiClientService.generateTextWithModel(
                    prompt, ollamaProperties.getFallbackModel());

            log.info("[Router][Tier2] Thành công với model: {}", ollamaProperties.getFallbackModel());
            return result;

        } catch (Exception tier2Ex) {
            log.warn("[Router][Tier2] Model {} cũng thất bại: {}. Chuyển sang Gemini Cloud (Tier 3)...",
                    ollamaProperties.getFallbackModel(), tier2Ex.getMessage());
        }

        // ─── TIER 3: Google Gemini Cloud ─────────────────────────────────────
        // Áp dụng Throttle 15s TRƯỚC khi gọi Gemini để tránh Rate Limit 429 Free Tier
        try {
            log.info("[Router][Tier3] Fallback sang Gemini Cloud. Throttling 15s để tránh Rate Limit 429...");
            Thread.sleep(15_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[Router][Tier3] Throttling bị gián đoạn. Tiếp tục gọi Gemini...");
        }

        try {
            log.info("[Router][Tier3] Đang gọi Gemini Cloud...");
            String result = geminiApiClientService.generateText(prompt);
            log.info("[Router][Tier3] Thành công với Gemini Cloud.");
            return result;

        } catch (Exception tier3Ex) {
            log.error("[Router][Tier3] Gemini Cloud cũng thất bại: {}", tier3Ex.getMessage());
        }

        // ─── ALL FAILED ───────────────────────────────────────────────────────
        String errorMessage = "Tất cả LLM provider đều thất bại: "
                + "Ollama(" + ollamaProperties.getPrimaryModel() + "), "
                + "Ollama(" + ollamaProperties.getFallbackModel() + "), "
                + "Gemini Cloud.";
        log.error("[Router] {}", errorMessage);

        throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX, errorMessage);
    }

    /**
     * Định tuyến AI & Fallback cho quá trình sinh Test Case.
     * Thu thập token và cập nhật trạng thái SUCCESS vào AiJobLog sau khi xong.
     */
    public String routeAndExecute(String prompt, UUID jobId) {
        String modelName;
        Integer tokenInput = prompt.length() / 4; // Giả lập token input do Ollama không trả về
        Integer tokenOutput = 0;
        String result;

        // TIER 1 - ƯU TIÊN: Ollama Local
        try {
            modelName = ollamaProperties.getPrimaryModel();
            log.info("[Router][Tier1] Đang gọi Ollama model: {}", modelName);
            result = ollamaApiClientService.generateTextWithModel(prompt, modelName);
            tokenOutput = result.length() / 4; // Giả lập token output
            log.info("[Router][Tier1] Thành công với Ollama model: {}", modelName);

        } catch (Exception ex) {
            log.warn("[Router][Tier1] Ollama thất bại: {}. Đang chuyển hướng sang Gemini...", ex.getMessage());

            // FALLBACK LOGIC: Chuyển sang Tier 2 (Gemini Cloud)
            try {
                modelName = "gemini-1.5-flash";
                log.info("[Router][Tier2] Đang gọi Gemini Cloud...");

                // Tránh rate limit
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse geminiResponse = geminiApiClientService
                        .sendFullPrompt(prompt);

                result = geminiResponse.extractText();

                if (geminiResponse.getUsageMetadata() != null) {
                    tokenInput = geminiResponse.getUsageMetadata().getPromptTokenCount();
                    tokenOutput = geminiResponse.getUsageMetadata().getCandidatesTokenCount();
                }

                log.info("[Router][Tier2] Thành công với Gemini Cloud.");

            } catch (Exception geminiEx) {
                log.error("[Router][Tier2] Gemini Cloud cũng thất bại: {}", geminiEx.getMessage());
                throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX, "Cả Ollama và Gemini đều thất bại.");
            }
        }

        // Cập nhật AiJobLog: set SUCCESS và lưu tokens
        try {
            aiJobLogService.markJobAsSuccess(jobId, tokenInput, tokenOutput, modelName);
            log.info("[Router] Đã cập nhật JobLog {} thành SUCCESS", jobId);
        } catch (Exception e) {
            log.warn("[Router] Không thể cập nhật JobLog {}: {}", jobId, e.getMessage());
        }

        return result;
    }
}