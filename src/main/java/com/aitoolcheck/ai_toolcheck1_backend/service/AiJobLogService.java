package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.CreateAiJobLogRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobStatisticResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;

import java.util.List;
import java.util.UUID;

public interface AiJobLogService {

    List<AiJobLogResponse> getJobLogs(UUID projectId);


    /**
     * Khởi tạo một Job với trạng thái PENDING và đẩy thông tin (message) vào
     * RabbitMQ queue.
     * Hàm này được thiết kế để xử lý cực nhanh (non-blocking) nhằm trả về phản hồi
     * cho người dùng ngay lập tức,
     * quá trình xử lý AI thực tế sẽ được worker lấy từ queue ra và thực thi ngầm.
     */
    AiJobLogResponse createPendingJobAndTriggerAi(String promptText, String skillCode,
            UUID projectId, UUID sourceFileId, UUID apiEndpointId);

    /**
     * Overload with scanBatchId — dùng bởi SourceDocumentationOrchestratorServiceImpl.
     * Persists scanBatchId vào ai_job_log để FE có thể group/poll theo batch.
     */
    AiJobLogResponse createPendingJobAndTriggerAi(String promptText, String skillCode,
            UUID projectId, UUID sourceFileId, UUID apiEndpointId, UUID scanBatchId);

    int triggerEnrichmentForProject(UUID projectId);

    AiJobLogResponse createPendingJob(CreateAiJobLogRequest request);

    AiJobLog createPendingJobIfNotExists(
            UUID projectId,
            UUID apiEndpointId,
            JobType jobType,
            UUID aiSkillId,
            String modelName,
            boolean forceRegenerate
    );

    void markJobAsRunning(UUID id);

    void updateTokens(UUID id, Integer tokenInput, Integer tokenOutput);

    void updateAiModelUsed(UUID id, String aiModelUsed);

    void markJobAsSuccess(UUID id, Integer tokenInput, Integer tokenOutput);

    void markJobAsSuccess(UUID id, Integer tokenInput, Integer tokenOutput, String modelName);

    void markJobAsSuccess(UUID id, Integer tokenInput, Integer tokenOutput, String modelName, String message);

    void markJobAsFailed(UUID id, String errorMessage);

    AiJobLogResponse getJobById(UUID id);

    AiJobStatisticResponse getJobStatistics();
}
