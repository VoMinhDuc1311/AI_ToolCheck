package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.CreateAiJobLogRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobStatisticResponse;

import java.util.UUID;

public interface AiJobLogService {

    /**
     * Khởi tạo một Job với trạng thái PENDING và đẩy thông tin (message) vào
     * RabbitMQ queue.
     * Hàm này được thiết kế để xử lý cực nhanh (non-blocking) nhằm trả về phản hồi
     * cho người dùng ngay lập tức,
     * quá trình xử lý AI thực tế sẽ được worker lấy từ queue ra và thực thi ngầm.
     */
    AiJobLogResponse createPendingJobAndTriggerAi(String promptText, String skillCode,
            UUID projectId, UUID sourceFileId, UUID apiEndpointId);

    int triggerEnrichmentForProject(UUID projectId);

    AiJobLogResponse createPendingJob(CreateAiJobLogRequest request);

    void markJobAsRunning(UUID id);

    void updateTokens(UUID id, Integer tokenInput, Integer tokenOutput);

    void markJobAsSuccess(UUID id, Integer tokenInput, Integer tokenOutput);

    void markJobAsFailed(UUID id, String errorMessage);

    AiJobLogResponse getJobById(UUID id);

    AiJobStatisticResponse getJobStatistics();
}
