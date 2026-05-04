package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.RabbitMQConfig;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.LegacyInferenceLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * RabbitMQ Consumer — điều phối luồng xử lý AI Task.
 *
 * <h3>Luồng Audit Trail hoàn chỉnh</h3>
 * <pre>
 * SUCCESS path:
 *   Gemini → rawString → Parser → DTO → persistLegacyInference()
 *                                           └─ createLog(SUCCESS) per endpoint
 *
 * FAILED path (AiJsonParseException):
 *   Gemini → rawString → Parser THROW
 *       └─ updateJobStatus(FAILED)
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
            aiJobLogService.markJobAsRunning(jobLog.getId());

            if ("legacy_code_reader".equalsIgnoreCase(message.getSkillCode()) || "SKILL_0".equalsIgnoreCase(message.getSkillCode())) {
                rawAiResponse = executeLegacyCodeReader(message, jobLog);
            } else {
                log.info("[RabbitMQ] SkillCode '{}' chưa hỗ trợ — bỏ qua.", message.getSkillCode());
                aiJobLogService.markJobAsFailed(jobLog.getId(), "SkillCode chưa hỗ trợ: " + message.getSkillCode());
            }

        } catch (AiJsonParseException jsonEx) {
            String errorDetail = "[" + jsonEx.getErrorType() + "] " + jsonEx.getMessage();
            log.warn("[RabbitMQ] AI Data Error — JobId: {}, Lý do: {}", message.getJobId(), errorDetail);
            
            if (jobLog != null) {
                aiJobLogService.markJobAsFailed(jobLog.getId(), errorDetail);
            }
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

        com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse response = 
                geminiApiClientService.getFullAiResponse(message.getPromptText());
        
        Integer tokenInput = 0;
        Integer tokenOutput = 0;
        if (response.getUsageMetadata() != null) {
            tokenInput = response.getUsageMetadata().getPromptTokenCount() != null ? response.getUsageMetadata().getPromptTokenCount() : 0;
            tokenOutput = response.getUsageMetadata().getCandidatesTokenCount() != null ? response.getUsageMetadata().getCandidatesTokenCount() : 0;
        }

        // Cập nhật Token ngay lập tức vào DB để không bị thất thoát nếu Parser ném lỗi
        aiJobLogService.updateTokens(jobLog.getId(), tokenInput, tokenOutput);

        String rawAiResponse = response.extractText();

        String cleanJson = aiJsonParserService.extractAndSanitizeJson(rawAiResponse);
        AiInferenceResultDto result = aiJsonParserService.parseToDto(cleanJson);

        UUID projectId    = parseUuidOrNull(message.getProjectId());
        UUID sourceFileId = parseUuidOrNull(message.getSourceFileId());

        SourceProject projectRef = sourceProjectRepository.getReferenceById(projectId);

        persistenceService.persistLegacyInference(
                projectRef,
                sourceFileId,
                rawAiResponse,
                cleanJson,
                result
        );

        aiJobLogService.markJobAsSuccess(jobLog.getId(), tokenInput, tokenOutput);

        log.info("[RabbitMQ][LegacyCodeReader] Hoàn thành JobId: {}, {} endpoint(s) đã lưu.",
                 jobLog.getId(), result.getEndpoints().size());

        return rawAiResponse;
    }

    // =========================================================================
    // Audit Log FAILED
    // =========================================================================

    /**
     * Ghi Audit Log FAILED khi Parser throw AiJsonParseException.
     * <p>
     * Nếu Gemini đã kịp trả về (rawAiResponse != null), lưu nguyên văn vào DB
     * — đây là bằng chứng AI ảo giác để debug ngày hôm sau.
     * Nếu Gemini chưa kịp trả về (lỗi ở bước gọi API), bỏ qua việc ghi Log này.
     * </p>
     */
    private void recordFailedAuditLog(AiTaskMessage message,
                                       String rawAiResponse,
                                       AiJsonParseException jsonEx) {
        if (rawAiResponse == null) {
            log.warn("[RabbitMQ] rawAiResponse=null — Gemini chưa kịp trả về. Bỏ qua Audit Log FAILED.");
            return;
        }

        try {
            UUID projectId   = parseUuidOrNull(message.getProjectId());
            UUID sourceFileId = parseUuidOrNull(message.getSourceFileId());

            if (projectId == null) {
                log.warn("[RabbitMQ] projectId null trong message — không thể ghi Audit Log FAILED.");
                return;
            }

            legacyInferenceLogService.createLog(
                    projectId,
                    sourceFileId,
                    null,                          // Chưa có ApiEndpoint (parse thất bại)
                    rawAiResponse,                 // Bằng chứng AI ảo giác
                    null,                          // Chưa có cleanJson
                    null,                          // Chưa có confidence
                    LogStatus.FAILED,
                    jsonEx.getErrorType().name()   // Ví dụ: "INVALID_JSON_SYNTAX"
            );

            log.info("[RabbitMQ] Audit Log FAILED đã ghi — ErrorType: {}", jsonEx.getErrorType());

        } catch (Exception auditEx) {
            // Lỗi khi ghi Audit Log KHÔNG được phép làm sập luồng chính
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

    private void updateJobStatus(AiJobLog jobLog, ExecutionStatus status, String errorMsg) {
        jobLog.setExecutionStatus(status);
        if (errorMsg != null) jobLog.setErrorMessage(errorMsg);
        if (status == ExecutionStatus.SUCCESS || status == ExecutionStatus.FAILED) {
            jobLog.setCompletedAt(LocalDateTime.now());
        }
        aiJobLogRepository.save(jobLog);
        log.debug("[RabbitMQ] JobLog {} → {}", jobLog.getId(), status);
    }

    /**
     * SNIPPET: Demonstrates safely extracting token metrics from GeminiResponse
     * and passing them to aiJobLogService.markJobAsSuccess with null-safety.
     * 
     * @param jobId The UUID of the job
     * @param response The parsed GeminiResponse object
     * @param aiJobLogService The injected AiJobLogService
     */
    private void finalizeJobWithTokensSnippet(UUID jobId, com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse response, com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService aiJobLogService) {
        Integer tokenInput = 0;
        Integer tokenOutput = 0;

        if (response != null && response.getUsageMetadata() != null) {
            com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse.UsageMetadata metadata = response.getUsageMetadata();
            tokenInput = metadata.getPromptTokenCount() != null ? metadata.getPromptTokenCount() : 0;
            tokenOutput = metadata.getCandidatesTokenCount() != null ? metadata.getCandidatesTokenCount() : 0;
        }

        aiJobLogService.markJobAsSuccess(jobId, tokenInput, tokenOutput);
    }

    /** Parse UUID an toàn — trả null nếu chuỗi null hoặc không hợp lệ. */
    private UUID parseUuidOrNull(String uuidStr) {
        if (uuidStr == null || uuidStr.isBlank()) return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            log.warn("[RabbitMQ] UUID không hợp lệ: '{}' — dùng null.", uuidStr);
            return null;
        }
    }
}