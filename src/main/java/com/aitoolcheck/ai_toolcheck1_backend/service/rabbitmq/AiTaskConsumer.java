package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.RabbitMQConfig;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
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
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OllamaApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.LegacyInferenceLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.DocumentEnrichmentService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiEndpointService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;

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
    // Injected for legacy_code_reader fallback routing (Gemini → Ollama)
    private final AiModelRouterService aiModelRouterService;
    private final OllamaProperties ollamaProperties;
    private final OllamaApiClientService ollamaApiClientService;
    private final GeminiProperties geminiProperties;
    private final SourceFileRepository sourceFileRepository;

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
     * Helper to build the legacy code reader prompt containing instructions, file metadata, and the raw code.
     *
     * <p>Fix 2: Prompt yêu cầu AI trả về requestSchema và responseSchema với danh sách fields
     * nếu AI suy luận được. Output phải là JSON thuần túy, không markdown, không giải thích.</p>
     *
     * <p>Contract JSON mong muốn:
     * <pre>
     * {
     *   "endpoints": [
     *     {
     *       "path": "/api/orders",
     *       "httpMethod": "POST",
     *       "description": "Create a new order",
     *       "authRequired": true,
     *       "source": { "className": "OrderController", "methodName": "createOrder" },
     *       "parameters": [
     *         { "name": "tenantId", "in": "PATH", "type": "String", "required": true, "example": "t-001" }
     *       ],
     *       "requestSchema": {
     *         "schemaName": "CreateOrderRequest",
     *         "fields": [
     *           { "fieldName": "customerId", "dataType": "String", "required": true, "nullable": false }
     *         ]
     *       },
     *       "responseSchema": {
     *         "schemaName": "OrderResponse",
     *         "fields": [
     *           { "fieldName": "id", "dataType": "String", "required": true, "nullable": false },
     *           { "fieldName": "status", "dataType": "String", "required": true, "nullable": false }
     *         ]
     *       }
     *     }
     *   ]
     * }
     * </pre>
     * </p>
     */
    private String buildLegacyCodeReaderPrompt(String promptText, SourceFile sourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Senior Backend Engineer analyzing Java source code to extract API metadata.\n");
        sb.append("You MUST respond ONLY with a valid JSON object. NO markdown, NO explanation, NO text outside JSON.\n\n");
        sb.append("Task: Analyze the provided Java source code and extract all HTTP API endpoints.\n\n");

        if (promptText != null && !promptText.isBlank()) {
            sb.append("Additional context:\n").append(promptText).append("\n\n");
        }

        sb.append("Source File Metadata:\n");
        sb.append("- File Name: ").append(sourceFile.getFileName()).append("\n");
        sb.append("- File Path: ").append(sourceFile.getFilePath()).append("\n");
        sb.append("- File Type: ").append(sourceFile.getFileType()).append("\n\n");

        sb.append("Source Code to Analyze:\n");
        sb.append("```java\n");
        sb.append(sourceFile.getSourceContent()).append("\n");
        sb.append("```\n\n");

        sb.append("Extraction Rules:\n");
        sb.append("1. Detect all HTTP endpoints: Spring (@RestController, @Controller, @RequestMapping, @GetMapping, @PostMapping, etc.), JAX-RS (@Path, @GET, @POST), Servlet (doGet, doPost), Struts Action, or any custom routing pattern.\n");
        sb.append("2. For each endpoint extract: path, httpMethod (GET/POST/PUT/DELETE/PATCH), description (inferred), authRequired (inferred).\n");
        sb.append("3. Extract parameters: name, in (PATH/QUERY/HEADER/BODY/COOKIE), type (Java type), required, example.\n");
        sb.append("4. Extract requestSchema if you can infer the request body object: schemaName, fields (fieldName, dataType, required, nullable).\n");
        sb.append("5. Extract responseSchema if you can infer the response object: schemaName, fields (fieldName, dataType, required, nullable).\n");
        sb.append("6. If you cannot determine a schema, omit requestSchema and/or responseSchema entirely — do NOT fabricate.\n");
        sb.append("7. If you cannot determine fields for a schema, return fields as empty array [].\n");
        sb.append("8. Return ONLY JSON. No markdown fences, no preamble, no commentary.\n\n");

        sb.append("Required JSON structure:\n");
        sb.append("{\n");
        sb.append("  \"endpoints\": [\n");
        sb.append("    {\n");
        sb.append("      \"path\": \"/api/resource\",\n");
        sb.append("      \"httpMethod\": \"POST\",\n");
        sb.append("      \"description\": \"brief description\",\n");
        sb.append("      \"authRequired\": false,\n");
        sb.append("      \"source\": { \"className\": \"MyController\", \"methodName\": \"myMethod\" },\n");
        sb.append("      \"parameters\": [\n");
        sb.append("        { \"name\": \"id\", \"in\": \"PATH\", \"type\": \"String\", \"required\": true, \"example\": \"123\" }\n");
        sb.append("      ],\n");
        sb.append("      \"requestSchema\": {\n");
        sb.append("        \"schemaName\": \"MyRequestDto\",\n");
        sb.append("        \"fields\": [\n");
        sb.append("          { \"fieldName\": \"fieldA\", \"dataType\": \"String\", \"required\": true, \"nullable\": false }\n");
        sb.append("        ]\n");
        sb.append("      },\n");
        sb.append("      \"responseSchema\": {\n");
        sb.append("        \"schemaName\": \"MyResponseDto\",\n");
        sb.append("        \"fields\": [\n");
        sb.append("          { \"fieldName\": \"id\", \"dataType\": \"String\", \"required\": true, \"nullable\": false }\n");
        sb.append("        ]\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String executeLegacyCodeReader(AiTaskMessage message, AiJobLog jobLog) {
        log.info("[LegacyCodeReader] Starting. Message jobId: [{}], sourceFileId: [{}]",
                message.getJobId(), message.getSourceFileId());

        // 1. Validate sourceFileId is actually present in message
        if (message.getSourceFileId() == null || message.getSourceFileId().trim().isEmpty()) {
            String errorMsg = "Missing required parameter 'sourceFileId' in the task message.";
            log.error("[LegacyCodeReader] ❌ {}", errorMsg);
            aiJobLogService.markJobAsFailed(jobLog.getId(), errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        UUID sourceFileId;
        try {
            sourceFileId = UUID.fromString(message.getSourceFileId());
        } catch (IllegalArgumentException e) {
            String errorMsg = "Invalid UUID format for 'sourceFileId': " + message.getSourceFileId();
            log.error("[LegacyCodeReader] ❌ {}", errorMsg);
            aiJobLogService.markJobAsFailed(jobLog.getId(), errorMsg);
            throw e;
        }

        // 2. Retrieve SourceFile from database
        SourceFile sourceFile = sourceFileRepository.findById(sourceFileId)
                .orElse(null);
        if (sourceFile == null) {
            String errorMsg = "SourceFile not found with ID: " + sourceFileId;
            log.error("[LegacyCodeReader] ❌ {}", errorMsg);
            aiJobLogService.markJobAsFailed(jobLog.getId(), errorMsg);
            throw new ResourceNotFoundException(errorMsg);
        }

        // Validate activeFlag & deletedFlag
        if (Boolean.FALSE.equals(sourceFile.getActiveFlag()) || Boolean.TRUE.equals(sourceFile.getDeletedFlag())) {
            String errorMsg = String.format("SourceFile is inactive or deleted. ActiveFlag: %s, DeletedFlag: %s",
                    sourceFile.getActiveFlag(), sourceFile.getDeletedFlag());
            log.error("[LegacyCodeReader] ❌ {}", errorMsg);
            aiJobLogService.markJobAsFailed(jobLog.getId(), errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        // Validate Project match
        UUID messageProjectId = parseUuidOrNull(message.getProjectId());
        if (sourceFile.getSourceProject() == null || !sourceFile.getSourceProject().getId().equals(messageProjectId)) {
            String errorMsg = String.format("SourceFile project ID mismatch. Expected project: %s, got: %s",
                    messageProjectId, sourceFile.getSourceProject() != null ? sourceFile.getSourceProject().getId() : null);
            log.error("[LegacyCodeReader] ❌ {}", errorMsg);
            aiJobLogService.markJobAsFailed(jobLog.getId(), errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        // Validate content
        String sourceContent = sourceFile.getSourceContent();
        if (sourceContent == null || sourceContent.trim().isEmpty()) {
            String errorMsg = "SourceFile content is empty for ID: " + sourceFileId;
            log.error("[LegacyCodeReader] ❌ {}", errorMsg);
            aiJobLogService.markJobAsFailed(jobLog.getId(), errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        // 3. Build Final Prompt
        String finalPrompt = buildLegacyCodeReaderPrompt(message.getPromptText(), sourceFile);

        // Add focused logs for verification:
        int promptTextLength = message.getPromptText() != null ? message.getPromptText().length() : 0;
        int sourceContentLength = sourceContent.length();
        int finalPromptLength = finalPrompt.length();
        log.info("[LegacyCodeReader] Prompt metadata verification:");
        log.info("  ├─ sourceFileId: {}", sourceFileId);
        log.info("  ├─ filePath: {}", sourceFile.getFilePath());
        log.info("  ├─ fileName: {}", sourceFile.getFileName());
        log.info("  ├─ promptTextLength: {}", promptTextLength);
        log.info("  ├─ sourceContentLength: {}", sourceContentLength);
        log.info("  └─ finalPromptLength: {}", finalPromptLength);

        String rawAiResponse = null;
        String modelUsed = null;
        int tokenInput = 0;
        int tokenOutput = 0;
        AiInferenceResultDto result = null;
        String cleanJson = null;

        String geminiErrorSummary = null;
        String ollamaErrorSummary = null;

        // ── TIER 1: Gemini Cloud (primary) ───────────────────────────────────
        try {
            log.info("[LegacyCodeReader][Tier1] Calling Gemini Cloud (primary)... Model: {}", geminiProperties.getModel());
            modelUsed = geminiProperties.getModel();

            com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse geminiResponse =
                    geminiApiClientService.getFullAiResponse(finalPrompt);

            // Extract token counts from Gemini metadata
            if (geminiResponse.getUsageMetadata() != null) {
                tokenInput = geminiResponse.getUsageMetadata().getPromptTokenCount() != null
                        ? geminiResponse.getUsageMetadata().getPromptTokenCount() : 0;
                tokenOutput = geminiResponse.getUsageMetadata().getCandidatesTokenCount() != null
                        ? geminiResponse.getUsageMetadata().getCandidatesTokenCount() : 0;
            } else {
                tokenInput = finalPrompt.length() / 4;
                tokenOutput = geminiResponse.extractText().length() / 4;
            }

            rawAiResponse = geminiResponse.extractText();
            log.info("[LegacyCodeReader][Tier1] Gemini API response succeeded. Length: {} chars.", rawAiResponse.length());

            // Validate and parse Gemini response
            cleanJson = aiJsonParserService.extractAndSanitizeJson(rawAiResponse);
            result = aiJsonParserService.parseToDto(cleanJson);

            log.info("[LegacyCodeReader][Tier1] Gemini parsed and validated successfully. extracted endpoints: {}", result.getEndpoints().size());

        } catch (Exception geminiEx) {
            // Either provider call failed, OR parse / validation failed
            geminiErrorSummary = geminiEx.getMessage();
            log.warn("[LegacyCodeReader][Tier1] Gemini failed (Provider or Parser validation exception): {}. Falling back to Ollama...",
                    geminiEx.getMessage());

            // ── TIER 2: Ollama fallback ───────────────────────────────────────
            try {
                modelUsed = ollamaProperties.getPrimaryModel();
                log.info("[LegacyCodeReader][Tier2] Calling Ollama model: {}", modelUsed);

                rawAiResponse = ollamaApiClientService.generateText(finalPrompt);
                tokenInput = finalPrompt.length() / 4;
                tokenOutput = rawAiResponse.length() / 4;

                log.info("[LegacyCodeReader][Tier2] Ollama API response succeeded. Length: {} chars.", rawAiResponse.length());

                // Validate and parse Ollama response
                cleanJson = aiJsonParserService.extractAndSanitizeJson(rawAiResponse);
                result = aiJsonParserService.parseToDto(cleanJson);

                log.info("[LegacyCodeReader][Tier2] Ollama parsed and validated successfully. extracted endpoints: {}", result.getEndpoints().size());

            } catch (Exception ollamaEx) {
                ollamaErrorSummary = ollamaEx.getMessage();
                log.error("[LegacyCodeReader][Tier2] Ollama also failed: {}", ollamaEx.getMessage());

                // Check if the reason was empty endpoints (DTO validation failure)
                boolean geminiNoEndpoints = geminiErrorSummary != null && geminiErrorSummary.contains("endpoints must not be empty");
                boolean ollamaNoEndpoints = ollamaErrorSummary != null && ollamaErrorSummary.contains("endpoints must not be empty");

                String errorDetail;
                if (geminiNoEndpoints || ollamaNoEndpoints) {
                    errorDetail = String.format("No endpoints extracted from selected source file [ID: %s, Path: %s]",
                            sourceFileId, sourceFile.getFilePath());
                } else {
                    errorDetail = String.format(
                            "[LegacyCodeReader] All providers failed. Gemini: %s | Ollama: %s",
                            geminiErrorSummary, ollamaErrorSummary);
                }

                aiJobLogService.markJobAsFailed(jobLog.getId(), errorDetail);
                throw new AiJsonParseException(ErrorType.DTO_VALIDATION_FAILED, errorDetail, ollamaEx);
            }
        }

        // ── Update tokens and model used immediately ──
        aiJobLogService.updateTokens(jobLog.getId(), tokenInput, tokenOutput);
        aiJobLogService.updateAiModelUsed(jobLog.getId(), modelUsed);
        log.info("[LegacyCodeReader] Model used: {}, tokenIn={}, tokenOut={}", modelUsed, tokenInput, tokenOutput);

        // ── Persist ──────────────────────────────────────────────────────────
        UUID projectId = parseUuidOrNull(message.getProjectId());
        SourceProject projectRef = sourceProjectRepository.getReferenceById(projectId);

        persistenceService.persistLegacyInference(
                projectRef,
                sourceFileId,
                rawAiResponse,
                cleanJson,
                result);
        log.info("[LegacyCodeReader] Persisted inference data to database");

        // ── Mark SUCCESS ─────────────────────────────────────────────────────
        aiJobLogService.markJobAsSuccess(jobLog.getId(), tokenInput, tokenOutput, modelUsed);
        log.info("[LegacyCodeReader] ✓ Job marked SUCCESS (JobId: {}), model: {}", jobLog.getId(), modelUsed);

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