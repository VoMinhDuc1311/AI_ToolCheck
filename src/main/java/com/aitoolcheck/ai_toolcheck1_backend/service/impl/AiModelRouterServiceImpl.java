package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException.ErrorType;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OllamaApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelRouterServiceImpl implements AiModelRouterService {

    private final OllamaApiClientService ollamaApiClientService;
    private final GeminiApiClientService geminiApiClientService;
    private final OllamaProperties ollamaProperties;
    private final AiJobLogService aiJobLogService;
    private final AiOptimizationProperties aiOptimizationProperties;
    private final AiPayloadOptimizerService aiPayloadOptimizerService;
    private final GeminiProperties geminiProperties;

    @Override
    public String executeWithFallback(String prompt) {
        String optimizedPrompt = aiOptimizationProperties.isEnabled() ?
                aiPayloadOptimizerService.truncateIfNeeded(prompt, aiOptimizationProperties.getMaxPromptChars()) : prompt;

        boolean tryOllama = true;
        if (aiOptimizationProperties.isEnabled() && aiOptimizationProperties.getRouter().isFailFastLocalProvider()) {
            if (!ollamaApiClientService.isHealthy()) {
                log.warn("[Router] Ollama health check failed. Bỏ qua Ollama, chuyển thẳng sang Gemini.");
                tryOllama = false;
            }
        }

        if (tryOllama) {
            // ─── TIER 1: Ollama Primary — qwen3-coder:30b ────────────────────────
            try {
                log.info("[Router][Tier1] Đang gọi Ollama model: {} — prompt: {} chars",
                        ollamaProperties.getPrimaryModel(), optimizedPrompt.length());

                String result = ollamaApiClientService.generateTextWithModel(
                        optimizedPrompt, ollamaProperties.getPrimaryModel());

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
                        optimizedPrompt, ollamaProperties.getFallbackModel());

                log.info("[Router][Tier2] Thành công với model: {}", ollamaProperties.getFallbackModel());
                return result;

            } catch (Exception tier2Ex) {
                log.warn("[Router][Tier2] Model {} cũng thất bại: {}. Chuyển sang Gemini Cloud (Tier 3)...",
                        ollamaProperties.getFallbackModel(), tier2Ex.getMessage());
            }
        }

        // ─── TIER 3: Google Gemini Cloud ─────────────────────────────────────
        try {
            log.info("[Router][Tier3] Fallback sang Gemini Cloud. Throttling 15s để tránh Rate Limit 429...");
            Thread.sleep(15_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[Router][Tier3] Throttling bị gián đoạn. Tiếp tục gọi Gemini...");
        }

        try {
            log.info("[Router][Tier3] Đang gọi Gemini Cloud...");
            String result = geminiApiClientService.generateText(optimizedPrompt);
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

    @Override
    public String routeAndExecute(String prompt, UUID jobId) {
        String modelName = "Unknown";
        
        String optimizedPrompt = aiOptimizationProperties.isEnabled() ?
                aiPayloadOptimizerService.truncateIfNeeded(prompt, aiOptimizationProperties.getMaxPromptChars()) : prompt;

        Integer tokenInput = optimizedPrompt.length() / 4;
        Integer tokenOutput = 0;
        String result = null;

        boolean tryOllama = true;
        if (aiOptimizationProperties.isEnabled() && aiOptimizationProperties.getRouter().isFailFastLocalProvider()) {
            if (!ollamaApiClientService.isHealthy()) {
                log.warn("[Router] Ollama health check failed. Bỏ qua Ollama, chuyển thẳng sang Gemini cho jobId={}", jobId);
                tryOllama = false;
            }
        }

        if (tryOllama) {
            try {
                modelName = ollamaProperties.getPrimaryModel();
                log.info("[Router][Tier1] Đang gọi Ollama model: {}", modelName);
                result = ollamaApiClientService.generateTextWithModel(optimizedPrompt, modelName);
                tokenOutput = result.length() / 4;
                log.info("[Router][Tier1] Thành công với Ollama model: {}", modelName);

            } catch (Exception ex) {
                log.warn("[Router][Tier1] Ollama thất bại: {}. Đang chuyển hướng sang Gemini...", ex.getMessage());
                tryOllama = false;
            }
        }
 
        if (!tryOllama) {
            try {
                modelName = geminiProperties.getModel();
                log.info("[Router][Tier2] Đang gọi Gemini Cloud với model: {}...", modelName);

                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse geminiResponse = geminiApiClientService
                        .sendFullPrompt(optimizedPrompt);

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

        try {
            aiJobLogService.markJobAsSuccess(jobId, tokenInput, tokenOutput, modelName);
            log.info("[Router] Đã cập nhật JobLog {} thành SUCCESS", jobId);
        } catch (Exception e) {
            log.warn("[Router] Không thể cập nhật JobLog {}: {}", jobId, e.getMessage());
        }

        return result;
    }
}
