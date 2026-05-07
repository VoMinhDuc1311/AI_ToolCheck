package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.RabbitMQConfig;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiDocumentEnrichmentResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException.ErrorType; // ĐÃ FIX: Import ErrorType
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.LegacyInferenceLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.DocumentEnrichmentService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiEndpointService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * RabbitMQ Consumer — điều phối luồng xử lý AI Task.
 *
 * <h3>Luồng Audit Trail hoàn chỉnh</h3>
 * 
 * <pre>
 * SUCCESS path:
 *   Gemini → rawString → Parser → DTO → persistLegacyInference() / enrichDocumentVersionData()
 *                                           └─ createLog(SUCCESS) per endpoint
 *
 * FAILED path (AiJsonParseException):
 *   Gemini → rawString → Parser THROW
 *       └─ markJobAsFailed(FAILED)
 *       └─ createLog(FAILED, rawResponse, errorType)  ← ghi bằng chứng AI ảo giác
 * </pre>
 *
 * <h3>Quy tắc RabbitMQ</h3>
 * <p>
 * TUYỆT ĐỐI KHÔNG throw ra ngoài {@code @RabbitListener} —
 * sẽ gây infinite retry loop làm sập server.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskConsumer {

    private final GeminiApiClientService geminiApiClientService;
    private final AiJsonParserService aiJsonParserService;
    private final AiTaskPersistenceService persistenceService;
    private final LegacyInferenceLogService legacyInferenceLogService;
    private final AiJobLogRepository aiJobLogRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService aiJobLogService;
    private final DocumentEnrichmentService documentEnrichmentService;
    private final ApiEndpointService apiEndpointService;

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void processAiTask(AiTaskMessage message) {
        log.info("[RabbitMQ] Nhận AI Task — JobId: {}, SkillCode: {}",
                message.getJobId(), message.getSkillCode());

        AiJobLog jobLog = null;
        String rawAiResponse = null;

        try {
            jobLog = loadJobLog(message.getJobId());
            // 1. Chuyển trạng thái sang RUNNING
            aiJobLogService.markJobAsRunning(jobLog.getId());

            // 2. Định tuyến Skill (Smart Router)
            if ("legacy_code_reader".equalsIgnoreCase(message.getSkillCode())
                    || "SKILL_0".equalsIgnoreCase(message.getSkillCode())) {
                rawAiResponse = executeLegacyCodeReader(message, jobLog);
            }
            // Bắt gói tin của Tuần 5 (AI Skill 1 - Enrich Docs)
            else if ("enrich_api_doc".equalsIgnoreCase(message.getSkillCode())
                    || "SKILL_1".equalsIgnoreCase(message.getSkillCode())) {
                rawAiResponse = executeDocumentEnrichment(message, jobLog);
            } else {
                log.info("[RabbitMQ] SkillCode '{}' chưa hỗ trợ — bỏ qua.", message.getSkillCode());
                aiJobLogService.markJobAsFailed(jobLog.getId(), "SkillCode chưa hỗ trợ: " + message.getSkillCode());
            }

            // THẤT BẠI DO AI (FALLBACK)
        } catch (AiJsonParseException jsonEx) {
            String errorDetail = "[" + jsonEx.getErrorType() + "] " + jsonEx.getMessage();
            log.warn("[RabbitMQ] AI Data Error — JobId: {}, Lý do: {}", message.getJobId(), errorDetail);

            // Cập nhật Job thành FAILED
            if (jobLog != null) {
                aiJobLogService.markJobAsFailed(jobLog.getId(), errorDetail);
            }
            // Ghi bản ghi vào LegacyInferenceLog để Audit (Dùng chung cho cả Skill 0 và 1)
            recordFailedAuditLog(message, rawAiResponse, jsonEx);

        } catch (Exception e) {
            log.error("[RabbitMQ] System Error — JobId: {}, Lý do: {}", message.getJobId(), e.getMessage(), e);
            if (jobLog != null) {
                aiJobLogService.markJobAsFailed(jobLog.getId(), "System Error: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Skill Executors
    // =========================================================================

    /**
     * Thực thi Skill 0 và trả về {@code rawAiResponse} để Consumer lưu tham chiếu
     * — dùng cho Audit Log FAILED nếu bước sau đó throw exception.
     *
     * @return rawAiResponse — chuỗi thô từ Gemini, chưa qua Parser.
     */
    private String executeLegacyCodeReader(AiTaskMessage message, AiJobLog jobLog) {
        log.info("[RabbitMQ][LegacyCodeReader] Bắt đầu — độ dài source: {} ký tự",
                message.getPromptText().length());

        com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse response = geminiApiClientService
                .getFullAiResponse(message.getPromptText());

        Integer tokenInput = 0;
        Integer tokenOutput = 0;
        if (response.getUsageMetadata() != null) {
            tokenInput = response.getUsageMetadata().getPromptTokenCount() != null
                    ? response.getUsageMetadata().getPromptTokenCount()
                    : 0;
            tokenOutput = response.getUsageMetadata().getCandidatesTokenCount() != null
                    ? response.getUsageMetadata().getCandidatesTokenCount()
                    : 0;
        }

        // Cập nhật Token ngay lập tức vào DB để không bị thất thoát nếu Parser ném lỗi
        aiJobLogService.updateTokens(jobLog.getId(), tokenInput, tokenOutput);

        String rawAiResponse = response.extractText();

        String cleanJson = aiJsonParserService.extractAndSanitizeJson(rawAiResponse);
        AiInferenceResultDto result = aiJsonParserService.parseToDto(cleanJson);

        UUID projectId = parseUuidOrNull(message.getProjectId());
        UUID sourceFileId = parseUuidOrNull(message.getSourceFileId());

        SourceProject projectRef = sourceProjectRepository.getReferenceById(projectId);

        persistenceService.persistLegacyInference(
                projectRef,
                sourceFileId,
                rawAiResponse,
                cleanJson,
                result);

        aiJobLogService.markJobAsSuccess(jobLog.getId(), tokenInput, tokenOutput);

        log.info("[RabbitMQ][LegacyCodeReader] Hoàn thành JobId: {}, {} endpoint(s) đã lưu.",
                jobLog.getId(), result.getEndpoints().size());

        return rawAiResponse;
    }

    /**
     * Thực thi AI Skill 1 (Enrich Document) với kiến trúc Multi-Model + RAG.
     *
     * <p>
     * <b>Luồng mới (v2):</b>
     * <ol>
     * <li>Lấy {@code openApiFragment} từ Message.</li>
     * <li>Delegate toàn bộ sang
     * {@code documentEnrichmentService.enrichDocumentation()}.
     * Service này nội bộ: RAG Retrieval → Build Prompt → Router (Ollama/Gemini) →
     * Parse JSON.</li>
     * <li>Lưu kết quả vào DB qua {@code apiEndpointService}.</li>
     * <li>Đánh dấu Job là SUCCESS.</li>
     * </ol>
     *
     * <p>
     * <b>Không còn Thread.sleep cứng nhắc ở đây.</b>
     * Sleep 15s chỉ được áp dụng bên trong {@code AiModelRouterService} nếu và chỉ
     * nếu
     * hệ thống buộc phải fallback sang Gemini Cloud (Tier 3).
     * Các luồng Ollama Local (Tier 1, Tier 2) chạy với tốc độ tối đa.
     */
    private String executeDocumentEnrichment(AiTaskMessage message, AiJobLog jobLog) {
        log.info("[RabbitMQ][DocumentEnrichment] Bắt đầu xử lý JobId: {}. Router sẽ quyết định LLM.",
                jobLog.getId());

        // Bước 1: Lấy OpenAPI Fragment từ Message
        String openApiFragment = message.getPromptText();

        // Bước 2: Delegate sang DocumentEnrichmentService (RAG + Multi-Model Router bên
        // trong)
        // - Không Thread.sleep ở đây! Sleep chỉ xảy ra trong Router nếu dùng Gemini.
        String endpointId = message.getApiEndpointId();
        AiDocumentEnrichmentResponseDto resultDto = documentEnrichmentService.enrichDocumentation(endpointId,
                openApiFragment);

        // Bước 3: Token tracking — Ollama không cung cấp token counts, đặt = 0
        // (Trong tương lai có thể thêm ThreadLocal/RequestContext để truyền thông tin
        // này)
        int tokenInput = 0;
        int tokenOutput = 0;
        aiJobLogService.updateTokens(jobLog.getId(), tokenInput, tokenOutput);

        // Bước 4: Lưu kết quả vào DB
        UUID apiEndpointId = parseUuidOrNull(message.getApiEndpointId());
        if (apiEndpointId != null) {
            String reqJson = null;
            if (resultDto.getExampleRequestJson() != null) {
                reqJson = resultDto.getExampleRequestJson().isTextual()
                        ? resultDto.getExampleRequestJson().asText()
                        : resultDto.getExampleRequestJson().toString();
            }

            String resJson = null;
            if (resultDto.getExampleResponseJson() != null) {
                resJson = resultDto.getExampleResponseJson().isTextual()
                        ? resultDto.getExampleResponseJson().asText()
                        : resultDto.getExampleResponseJson().toString();
            }

            String openapiFragJson = null;
            if (resultDto.getOpenapiFragmentJson() != null) {
                openapiFragJson = resultDto.getOpenapiFragmentJson().isTextual()
                        ? resultDto.getOpenapiFragmentJson().asText()
                        : resultDto.getOpenapiFragmentJson().toString();
            }

            apiEndpointService.enrichEndpointData(
                    apiEndpointId,
                    resultDto.getSummary(),
                    resultDto.getDescription(),
                    reqJson,
                    resJson,
                    openapiFragJson,
                    jobLog.getId());
        } else {
            log.warn("[RabbitMQ][DocumentEnrichment] Không có ApiEndpoint ID trong message — bỏ qua lưu DB.");
        }

        // Bước 5: Chốt trạng thái SUCCESS
        aiJobLogService.markJobAsSuccess(jobLog.getId(), tokenInput, tokenOutput);

        log.info("[RabbitMQ][DocumentEnrichment] Hoàn thành JobId: {}. Summary: \"{}\"",
                jobLog.getId(),
                resultDto.getSummary() != null
                        ? resultDto.getSummary().substring(0,
                                Math.min(60, resultDto.getSummary().length())) + "..."
                        : "null");

        // Trả về summary để Consumer có thể log trong trường hợp lỗi downstream
        return resultDto.getSummary() != null ? resultDto.getSummary() : "";
    }

    // =========================================================================
    // Audit Log FAILED
    // =========================================================================

    /**
     * Ghi Audit Log FAILED khi Parser throw AiJsonParseException.
     */
    private void recordFailedAuditLog(AiTaskMessage message,
            String rawAiResponse,
            AiJsonParseException jsonEx) {
        if (rawAiResponse == null) {
            log.warn("[RabbitMQ] rawAiResponse=null — Gemini chưa kịp trả về. Bỏ qua Audit Log FAILED.");
            return;
        }

        try {
            UUID projectId = parseUuidOrNull(message.getProjectId());
            UUID sourceFileId = parseUuidOrNull(message.getSourceFileId());

            if (projectId == null) {
                log.warn("[RabbitMQ] projectId null trong message — không thể ghi Audit Log FAILED.");
                return;
            }

            legacyInferenceLogService.createLog(
                    projectId,
                    sourceFileId,
                    null, // Chưa có ApiEndpoint (parse thất bại)
                    rawAiResponse, // Bằng chứng AI ảo giác
                    null, // Chưa có cleanJson
                    null, // Chưa có confidence
                    LogStatus.FAILED,
                    jsonEx.getErrorType().name() // Ví dụ: "INVALID_JSON_SYNTAX"
            );

            log.info("[RabbitMQ] Audit Log FAILED đã ghi — ErrorType: {}", jsonEx.getErrorType());

        } catch (Exception auditEx) {
            log.error("[RabbitMQ] Không thể ghi Audit Log FAILED — Lý do: {}", auditEx.getMessage());
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private AiJobLog loadJobLog(String jobIdStr) {
        return aiJobLogRepository.findById(UUID.fromString(jobIdStr))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy JobLog: " + jobIdStr));
    }

    /** Parse UUID an toàn — trả null nếu chuỗi null hoặc không hợp lệ. */
    private UUID parseUuidOrNull(String uuidStr) {
        if (uuidStr == null || uuidStr.isBlank())
            return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            log.warn("[RabbitMQ] UUID không hợp lệ: '{}' — dùng null.", uuidStr);
            return null;
        }
    }
}