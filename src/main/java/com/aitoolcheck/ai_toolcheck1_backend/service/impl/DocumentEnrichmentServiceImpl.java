package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiDocumentEnrichmentResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException.ErrorType;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.DocumentEnrichmentService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.VectorSearchService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ai.AiPromptConstants;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Triển khai luồng AI Skill 1 — Làm giàu tài liệu API (Document Enrichment).
 *
 * <p><b>Luồng mới (v2 — Multi-Model + RAG):</b>
 * <ol>
 *   <li>Gọi {@link VectorSearchService} để tìm các ví dụ tương tự trong Vector Store (RAG Retrieval).</li>
 *   <li>Ghép RAG context vào Prompt chuẩn ({@link AiPromptConstants#ENRICH_DOC_SYSTEM_PROMPT}).</li>
 *   <li>Gọi {@link AiModelRouterService#executeWithFallback(String)} — Router tự quyết định
 *       Ollama Tier1 → Ollama Tier2 → Gemini Cloud.</li>
 *   <li>Đưa raw text qua {@link AiJsonParserService#parseJson} để ép kiểu sang DTO.</li>
 *   <li>Lưu embedding của nội dung vừa enrich vào Vector Store để làm giàu RAG context tương lai.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEnrichmentServiceImpl implements DocumentEnrichmentService {

    private final AiModelRouterService aiModelRouterService;
    private final VectorSearchService vectorSearchService;
    private final AiJsonParserService aiJsonParserService;
    private final GeminiApiClientService geminiApiClientService;

    // =========================================================================
    // PRIMARY ENTRY POINT (Multi-Model + RAG)
    // =========================================================================

    @Override
    // THAY ĐỔI: Thêm tham số apiEndpointId vào hàm
    public AiDocumentEnrichmentResponseDto enrichDocumentation(String apiEndpointId, String openApiFragment) {
        log.info("[DocumentEnrichment] Bắt đầu luồng RAG + Multi-Model Enrichment cho Endpoint ID: {}", apiEndpointId);

        if (openApiFragment == null || openApiFragment.isBlank()) {
            throw new IllegalArgumentException("[DocumentEnrichment] openApiFragment không được để trống.");
        }

        // ── Bước 1: RAG Retrieval — Tìm context tương tự từ Vector Store ──────
        log.info("[DocumentEnrichment] Bước 1: Tìm RAG context liên quan...");
        String ragContext = vectorSearchService.findSimilarContext(openApiFragment, 3);

        if (ragContext.isBlank()) {
            log.info("[DocumentEnrichment] Không có RAG context (Vector Store trống hoặc không tìm thấy kết quả).");
        } else {
            log.info("[DocumentEnrichment] Đã lấy RAG context — {} chars.", ragContext.length());
        }

        // ── Bước 2: Build Prompt với RAG Context ─────────────────────────────
        String finalPrompt = String.format(AiPromptConstants.ENRICH_DOC_SYSTEM_PROMPT,
                ragContext, openApiFragment);

        log.info("[DocumentEnrichment] Bước 2: Đã build Prompt — tổng {} chars.", finalPrompt.length());

        // ── Bước 3: Gọi Router (Ollama Tier1 → Tier2 → Gemini Cloud) ─────────
        log.info("[DocumentEnrichment] Bước 3: Gọi AiModelRouterService...");
        String rawAiText = aiModelRouterService.executeWithFallback(finalPrompt);
        log.info("[DocumentEnrichment] Bước 3: Router trả về {} chars.", rawAiText.length());

        // ── Bước 4: Parse JSON → DTO ─────────────────────────────────────────
        log.info("[DocumentEnrichment] Bước 4: Đưa vào AiJsonParserService để ép kiểu...");
        AiDocumentEnrichmentResponseDto resultDto = aiJsonParserService.parseJson(
                rawAiText, AiDocumentEnrichmentResponseDto.class);

        // ── Bước 5: Lưu Embedding vào Vector Store (Chống Rác + Gắn ID) ────────
        // THAY ĐỔI: Kiểm tra nếu summary rỗng hoặc quá ngắn thì không lưu để tránh làm "ngu" AI
        if (resultDto.getSummary() != null && !resultDto.getSummary().trim().isEmpty()) {
            String contentToStore = "API Metadata:\n" + openApiFragment
                    + "\n\nAI Summary:\n" + resultDto.getSummary();
            
            // THAY ĐỔI: Truyền apiEndpointId thay vì chữ null
            vectorSearchService.storeEmbedding(apiEndpointId, contentToStore);
            log.info("[DocumentEnrichment] Bước 5: Đã lưu embedding mới vào Vector Store (source_id: {}).", apiEndpointId);
        } else {
            log.warn("[DocumentEnrichment] Bước 5: Bỏ qua lưu Vector vì nội dung AI sinh ra rỗng (Tránh rác DB).");
        }

        log.info("[DocumentEnrichment] Hoàn thành xuất sắc! summary: \"{}\"",
                resultDto.getSummary() != null ? resultDto.getSummary().substring(0,
                        Math.min(80, resultDto.getSummary().length())) + "..." : "null");

        return resultDto;
    }

    // =========================================================================
    // BACKWARD-COMPAT
    // =========================================================================

    @Override
    @Deprecated(since = "v2-MultiModelRAG", forRemoval = false)
    public GeminiResponse getRawGeminiResponse(String openApiFragment) {
        if (openApiFragment == null || openApiFragment.isBlank()) {
            throw new IllegalArgumentException("[DocumentEnrichment] Metadata đầu vào không được để trống.");
        }
        String finalPrompt = String.format(AiPromptConstants.ENRICH_DOC_SYSTEM_PROMPT,
                "", openApiFragment);
        log.debug("[DocumentEnrichment][LegacyGemini] Gọi Gemini trực tiếp. Prompt: {} chars",
                finalPrompt.length());
        return geminiApiClientService.getFullAiResponse(finalPrompt);
    }

    private String extractTextSafely(GeminiResponse response) {
        try {
            if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
                throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX,
                        "Gemini trả về response rỗng hoặc không có candidate.");
            }
            return response.getCandidates().get(0)
                    .getContent().getParts().get(0)
                    .getText();
        } catch (NullPointerException | IndexOutOfBoundsException e) {
            log.error("[DocumentEnrichment] Không thể bóc tách text từ GeminiResponse.", e);
            throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX,
                    "Cấu trúc phản hồi từ Google bị lỗi/thiếu data.", e);
        }
    }
}