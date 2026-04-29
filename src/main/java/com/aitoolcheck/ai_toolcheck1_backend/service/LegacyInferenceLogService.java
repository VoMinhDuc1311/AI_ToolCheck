package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.LegacyInferenceLog;

import java.util.UUID;

/**
 * Contract cho Audit Trail Service — ghi lại toàn bộ vết của mỗi lần AI inference.
 */
public interface LegacyInferenceLogService {

    /**
     * Tạo và lưu một bản ghi Audit Trail cho một lần AI inference.
     *
     * @param projectId       ID của SourceProject.
     * @param sourceFileId    ID của SourceFile (nullable nếu không liên quan file cụ thể).
     * @param apiEndpointId   ID của ApiEndpoint đã lưu (nullable nếu chưa tạo endpoint).
     * @param rawResponse     Chuỗi thô từ Gemini — trước khi Parser xử lý.
     * @param cleanJson       JSON đã làm sạch — output của extractAndSanitizeJson().
     * @param confidenceScore Điểm tin cậy (0.0 – 1.0) từ AI, nullable.
     * @param status          Kết quả: SUCCESS hoặc FAILED.
     * @param errorType       Tên ErrorType nếu FAILED (nullable nếu SUCCESS).
     * @return Entity đã được lưu vào DB.
     */
    LegacyInferenceLog createLog(UUID projectId,
                                 UUID sourceFileId,
                                 UUID apiEndpointId,
                                 String rawResponse,
                                 String cleanJson,
                                 Double confidenceScore,
                                 LogStatus status,
                                 String errorType);
}
