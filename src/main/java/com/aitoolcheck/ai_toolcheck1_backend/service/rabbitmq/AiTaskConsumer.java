package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.RabbitMQConfig;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiDocumentEnrichmentResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException.ErrorType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiPersistenceException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.LegacyInferenceLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.DocumentEnrichmentService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiEndpointService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

/**
 * RabbitMQ Consumer — Phase 4: Quản lý trạng thái AI Job với Dual-AI Architecture.
 *
 * <h3>Nhiệm vụ chính</h3>
 * <ul>
 *   <li><b>Khi nhận message:</b> Cập nhật Job → PROCESSING (ACK thủ công)</li>
 *   <li><b>Thực thi:</b> Gọi logic xử lý AI (Ollama Tier 1 → Gemini Tier 2 fallback)</li>
 *   <li><b>Thành công:</b> Cập nhật SUCCESS + ghi ai_model_used + tokens</li>
 *   <li><b>Thất bại:</b> Bắt chi tiết lỗi (AiJsonParseException, AiPersistenceException, ...)</li>
 *   <li><b>ACK/NACK:</b> Đảm bảo không mất message khi server sập</li>
 * </ul>
 *
 * <h3>Luồng Audit Trail hoàn chỉnh</h3>
 * 
 * <pre>
 * SUCCESS path:
 *   Gemini → rawString → Parser → DTO → persistLegacyInference() / enrichDocumentVersionData()
 *                          ↓
 *   AiJobLog: SUCCESS + ai_model_used="gemini-1.5-pro" + tokenInput + tokenOutput
 *                          ↓
 *                     basicAck()
 *
 * FAILED path (AiJsonParseException):
 *   Gemini → rawString → Parser THROW
 *       ↓
 *   AiJobLog: FAILED + errorMessage=[ErrorType] {stacktrace}
 *       ↓
 *   createLog(FAILED, rawResponse, errorType)  ← Audit Log
 *       ↓
 *                   basicAck()  (thông báo đã xử lý lỗi)
 *
 * FAILED path (AiPersistenceException):
 *   Data persistence failed (DB constraint, network, ...)
 *       ↓
 *   AiJobLog: FAILED + errorMessage={full stacktrace}
 *       ↓
 *                   basicAck()
 * </pre>
 *
 * <h3>Quy tắc RabbitMQ</h3>
 * <p>
 * ✓ LUÔN gọi basicAck() hoặc basicNack() để xác nhận đã xử lý message.
 * ✗ KHÔNG throw ra ngoài @RabbitListener — sẽ gây infinite retry loop.
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
    private final TestCaseService testCaseService;

    // =========================================================================
    // ENTRY POINT - Phase 4: Quản lý trạng thái Job
    // =========================================================================

    /**
     * Entry point chính: Xử lý message từ RabbitMQ với manual ACK/NACK.
     * 
     * @param message         Payload từ queue
     * @param deliveryTag     Unique ID của message trong RabbitMQ
     * @param channel         RabbitMQ Channel để gọi basicAck/basicNack
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME, ackMode = "MANUAL")
    public void processAiTask(AiTaskMessage message,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                              Channel channel) {
        log.info("═══════════════════════════════════════════════════════════════════");
        log.info("[Phase 4] RabbitMQ Consumer nhận AI Task");
        log.info("  ├─ JobId: {}", message.getJobId());
        log.info("  ├─ SkillCode: {}", message.getSkillCode());
        log.info("  ├─ ProjectId: {}", message.getProjectId());
        log.info("  └─ DeliveryTag: {}", deliveryTag);
        log.info("═══════════════════════════════════════════════════════════════════");

        AiJobLog jobLog = null;
        String rawAiResponse = null;
        boolean successfullyProcessed = false;

        try {
            // 1. Load Job từ DB
            jobLog = loadJobLog(message.getJobId());
            log.info("[Phase 4 - STEP 1] ✓ Loaded JobLog: {} (Status: {})", 
                    jobLog.getId(), jobLog.getExecutionStatus());

            // 2. Cập nhật trạng thái → PROCESSING
            aiJobLogService.markJobAsRunning(jobLog.getId());
            log.info("[Phase 4 - STEP 2] ✓ Job transitioned to PROCESSING");

            // 3. Định tuyến Skill (Smart Router)
            log.info("[Phase 4 - STEP 3] ⚙️  Routing Skill: {}", message.getSkillCode());
            if ("legacy_code_reader".equalsIgnoreCase(message.getSkillCode())
                    || "SKILL_0".equalsIgnoreCase(message.getSkillCode())) {
                rawAiResponse = executeLegacyCodeReader(message, jobLog);
            }
            else if ("enrich_api_doc".equalsIgnoreCase(message.getSkillCode())
                    || "SKILL_1".equalsIgnoreCase(message.getSkillCode())) {
                rawAiResponse = executeDocumentEnrichment(message, jobLog);
            }
            else if ("GENERATE_TEST_CASE".equalsIgnoreCase(message.getSkillCode())) {
                rawAiResponse = testCaseService.generateTestCaseProcessing(
                    message.getApiEndpointId(), jobLog.getId());
            }
            else {
                log.warn("[Phase 4 - ERROR] ❌ SkillCode '{}' chưa hỗ trợ", message.getSkillCode());
                String unsupportedMsg = "SkillCode chưa hỗ trợ: " + message.getSkillCode();
                aiJobLogService.markJobAsFailed(jobLog.getId(), unsupportedMsg);
                successfullyProcessed = true;  // Đã xử lý xong (lỗi business, không cần retry)
                return;
            }

            // 4. SUCCESS — ACK message
            log.info("[Phase 4 - STEP 4] ✓ Job completed successfully");
            successfullyProcessed = true;

            // THẤT BẠI DO AI (Lỗi Parse JSON)
        } catch (AiJsonParseException jsonEx) {
            log.error("[Phase 4 - ERROR] ❌ AI JSON Parse Error");
            log.error("  ├─ ErrorType: {}", jsonEx.getErrorType());
            log.error("  ├─ Message: {}", jsonEx.getMessage());
            log.error("  └─ JobId: {}", message.getJobId());

            if (jobLog != null) {
                // Ghi chi tiết lỗi: ErrorType + stacktrace
                String errorDetail = formatDetailedError(
                    "[" + jsonEx.getErrorType() + "] " + jsonEx.getMessage(),
                    jsonEx
                );
                aiJobLogService.markJobAsFailed(jobLog.getId(), errorDetail);
            }
            // Ghi Audit Log FAILED để truy vết
            recordFailedAuditLog(message, rawAiResponse, jsonEx);
            successfullyProcessed = true;  // Đã xử lý xong error này

            // THẤT BẠI DO DATABASE (Lỗi Persistence)
        } catch (AiPersistenceException persistEx) {
            log.error("[Phase 4 - ERROR] ❌ AI Persistence Error (Database)");
            log.error("  ├─ Message: {}", persistEx.getMessage());
            log.error("  └─ JobId: {}", message.getJobId());

            if (jobLog != null) {
                // Ghi chi tiết lỗi: full stacktrace
                String errorDetail = formatDetailedError(
                    "Lỗi lưu dữ liệu vào Database: " + persistEx.getMessage(),
                    persistEx
                );
                aiJobLogService.markJobAsFailed(jobLog.getId(), errorDetail);
            }
            successfullyProcessed = true;

            // THẤT BẠI DO HỆ THỐNG (Lỗi không mong muốn)
        } catch (Exception e) {
            log.error("[Phase 4 - ERROR] ❌ System Error (Unexpected)");
            log.error("  ├─ Exception Type: {}", e.getClass().getName());
            log.error("  ├─ Message: {}", e.getMessage());
            log.error("  └─ JobId: {}", message.getJobId());
            log.error("  └─ Stacktrace:", e);

            if (jobLog != null) {
                String errorDetail = formatDetailedError(
                    "Lỗi hệ thống không mong muốn: " + e.getClass().getSimpleName(),
                    e
                );
                aiJobLogService.markJobAsFailed(jobLog.getId(), errorDetail);
            }
            successfullyProcessed = true;

        } finally {
            // 5. ACK/NACK message từ RabbitMQ
            try {
                if (successfullyProcessed) {
                    channel.basicAck(deliveryTag, false);
                    log.info("[Phase 4 - FINAL] ✓ Message ACK sent to RabbitMQ (DeliveryTag: {})", deliveryTag);
                } else {
                    // Nếu có lỗi unexpected, NACK để retry
                    channel.basicNack(deliveryTag, false, true);
                    log.warn("[Phase 4 - FINAL] ⚠️  Message NACK sent to RabbitMQ (DeliveryTag: {}) — sẽ retry", 
                             deliveryTag);
                }
            } catch (IOException ioEx) {
                log.error("[Phase 4 - CRITICAL] ❌ Lỗi gọi basicAck/basicNack — RabbitMQ Channel error", ioEx);
            }
        }
    }

    // =========================================================================
    // Skill Executors - Các phương thức thực thi Logic AI
    // =========================================================================

    /**
     * Skill 0: Legacy Code Reader (Gemini → Parse → Persist)
     * 
     * @return rawAiResponse từ Gemini (dùng cho audit log nếu error)
     */
    private String executeLegacyCodeReader(AiTaskMessage message, AiJobLog jobLog) {
        log.info("[LegacyCodeReader] Bắt đầu — độ dài source: {} ký tự",
                message.getPromptText().length());

        // Bước 1: Gọi Gemini API
        com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse response = geminiApiClientService
                .getFullAiResponse(message.getPromptText());

        // Bước 2: Trích xuất token counts
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

        // Bước 3: Cập nhật Token ngay (để không thất thoát nếu parser ném lỗi)
        aiJobLogService.updateTokens(jobLog.getId(), tokenInput, tokenOutput);
        log.info("[LegacyCodeReader] Updated tokens: input={}, output={}", tokenInput, tokenOutput);

        // Bước 4: Ghi lại Model đã dùng
        aiJobLogService.updateAiModelUsed(jobLog.getId(), "gemini-1.5-pro");
        log.info("[LegacyCodeReader] Recorded AI Model Used: gemini-1.5-pro");

        // Bước 5: Parse JSON từ response
        String rawAiResponse = response.extractText();
        String cleanJson = aiJsonParserService.extractAndSanitizeJson(rawAiResponse);
        AiInferenceResultDto result = aiJsonParserService.parseToDto(cleanJson);
        log.info("[LegacyCodeReader] Parsed {} endpoint(s)", result.getEndpoints().size());

        // Bước 6: Lưu vào DB
        UUID projectId = parseUuidOrNull(message.getProjectId());
        UUID sourceFileId = parseUuidOrNull(message.getSourceFileId());
        SourceProject projectRef = sourceProjectRepository.getReferenceById(projectId);

        persistenceService.persistLegacyInference(
                projectRef,
                sourceFileId,
                rawAiResponse,
                cleanJson,
                result);
        log.info("[LegacyCodeReader] Persisted to database");

        // Bước 7: Đánh dấu SUCCESS
        aiJobLogService.markJobAsSuccess(jobLog.getId(), tokenInput, tokenOutput);
        log.info("[LegacyCodeReader] ✓ Job marked SUCCESS (JobId: {})", jobLog.getId());

        return rawAiResponse;
    }

    /**
     * Skill 1: Document Enrichment (Multi-Model Router: Ollama Tier 1 → Gemini Tier 2)
     *
     * <p>
     * <b>Kiến trúc mới (Phase 4):</b>
     * <ol>
     * <li>Lấy OpenAPI Fragment từ Message</li>
     * <li>Delegate sang DocumentEnrichmentService (nó nội bộ chứa Router + Ollama/Gemini)</li>
     * <li>Token tracking — Ollama không cung cấp token counts, Gemini có</li>
     * <li>Lưu kết quả vào DB</li>
     * <li>Ghi ai_model_used (dựa vào router decision: "ollama-llama3" hoặc "gemini-1.5-pro")</li>
     * <li>Đánh dấu SUCCESS</li>
     * </ol>
     *
     * @return summary từ enrichment (dùng cho audit log nếu error)
     */
    private String executeDocumentEnrichment(AiTaskMessage message, AiJobLog jobLog) {
        log.info("[DocumentEnrichment] Bắt đầu xử lý JobId: {}. Router sẽ quyết định LLM.",
                jobLog.getId());

        // Bước 1: Lấy OpenAPI Fragment từ Message
        String openApiFragment = message.getPromptText();

        // Bước 2: Delegate sang DocumentEnrichmentService (RAG + Multi-Model Router bên trong)
        String endpointId = message.getApiEndpointId();
        AiDocumentEnrichmentResponseDto resultDto = documentEnrichmentService.enrichDocumentation(endpointId,
                openApiFragment);
        log.info("[DocumentEnrichment] Enrichment completed by DocumentEnrichmentService");

        // Bước 3: Token tracking
        // TODO: Nếu router dùng Gemini, cần lấy token counts từ resultDto.
        // Hiện tại giả định Ollama (Tier 1) không cung cấp token counts.
        int tokenInput = 0;
        int tokenOutput = 0;
        aiJobLogService.updateTokens(jobLog.getId(), tokenInput, tokenOutput);
        log.info("[DocumentEnrichment] Updated tokens: input={}, output={} (Ollama doesn't provide)", 
                 tokenInput, tokenOutput);

        // Bước 4: Ghi lại Model đã dùng
        // TODO: Router service nên trả về tên model trong resultDto.
        // Giả định mặc định dùng Ollama nếu không có thông tin
        String aiModelUsed = resultDto.getAiModelUsed() != null 
            ? resultDto.getAiModelUsed() 
            : "ollama-llama3";
        aiJobLogService.updateAiModelUsed(jobLog.getId(), aiModelUsed);
        log.info("[DocumentEnrichment] Recorded AI Model Used: {}", aiModelUsed);

        // Bước 5: Lưu kết quả vào DB
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

            apiEndpointService.enrichEndpointDataFromAiJob(
                    apiEndpointId,
                    resultDto.getSummary(),
                    resultDto.getDescription(),
                    reqJson,
                    resJson,
                    openapiFragJson,
                    jobLog.getId());
            log.info("[DocumentEnrichment] Saved enrichment data to DB for endpoint: {}", apiEndpointId);
        } else {
            log.warn("[DocumentEnrichment] Không có ApiEndpoint ID trong message — bỏ qua lưu DB.");
        }

        // Bước 6: Đánh dấu SUCCESS
        aiJobLogService.markJobAsSuccess(jobLog.getId(), tokenInput, tokenOutput);
        log.info("[DocumentEnrichment] ✓ Job marked SUCCESS (JobId: {})", jobLog.getId());

        return resultDto.getSummary() != null ? resultDto.getSummary() : "";
    }

    // =========================================================================
    // Audit Log & Error Handling
    // =========================================================================

    /**
     * Ghi Audit Log FAILED khi Parser throw AiJsonParseException.
     */
    private void recordFailedAuditLog(AiTaskMessage message,
            String rawAiResponse,
            AiJsonParseException jsonEx) {
        if (rawAiResponse == null) {
            log.warn("[Audit] rawAiResponse=null — Gemini chưa kịp trả về. Bỏ qua Audit Log FAILED.");
            return;
        }

        try {
            UUID projectId = parseUuidOrNull(message.getProjectId());
            UUID sourceFileId = parseUuidOrNull(message.getSourceFileId());

            if (projectId == null) {
                log.warn("[Audit] projectId null trong message — không thể ghi Audit Log FAILED.");
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

            log.info("[Audit] Audit Log FAILED recorded — ErrorType: {}", jsonEx.getErrorType());

        } catch (Exception auditEx) {
            log.error("[Audit] Không thể ghi Audit Log FAILED — Lý do: {}", auditEx.getMessage());
        }
    }

    /**
     * Format lỗi chi tiết: Bao gồm message + stacktrace đầy đủ.
     * Dùng để lưu vào AiJobLog.errorMessage để debug sau này.
     */
    private String formatDetailedError(String message, Throwable throwable) {
        String stackTrace = ExceptionUtils.getStackTrace(throwable);
        // Giới hạn độ dài để không vượt quá dung lượng TEXT column (MySQL)
        String truncatedStackTrace = stackTrace.length() > 3000
            ? stackTrace.substring(0, 3000) + "\n... [truncated]"
            : stackTrace;
        return message + "\n\n--- Full Stacktrace ---\n" + truncatedStackTrace;
    }

    // =========================================================================
    // Utility & Helpers
    // =========================================================================

    /**
     * Load AiJobLog từ DB theo jobIdStr.
     * Throw RuntimeException nếu không tìm thấy.
     */
    private AiJobLog loadJobLog(String jobIdStr) {
        return aiJobLogRepository.findById(UUID.fromString(jobIdStr))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy JobLog: " + jobIdStr));
    }

    /**
     * Parse UUID an toàn — trả null nếu chuỗi null hoặc không hợp lệ.
     */
    private UUID parseUuidOrNull(String uuidStr) {
        if (uuidStr == null || uuidStr.isBlank())
            return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            log.warn("[Utility] UUID không hợp lệ: '{}' — dùng null.", uuidStr);
            return null;
        }
    }
}