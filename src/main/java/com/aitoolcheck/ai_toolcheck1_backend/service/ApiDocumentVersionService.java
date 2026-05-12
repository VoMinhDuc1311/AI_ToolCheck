package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.req.UpdateApiDocumentVersionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionDetailResponse;

import java.util.UUID;

public interface ApiDocumentVersionService {

    ApiDocumentVersionDetailResponse getById(UUID versionId);

    ApiDocumentVersionDetailResponse update(UUID versionId, UpdateApiDocumentVersionRequest request);

    /**
     * Cập nhật dữ liệu tài liệu API sau khi được AI làm giàu (Enrich - AI Skill 1).
     * Hàm này được gọi từ RabbitMQ Consumer sau khi parse thành công JSON từ
     * Gemini.
     * 
     * @param id          ID của ApiDocumentVersion cần cập nhật
     * @param summary     Tóm tắt API do AI sinh ra
     * @param description Mô tả chi tiết API do AI sinh ra
     * @param reqJson     Ví dụ JSON request do AI sinh ra (đã được parse và map)
     * @param resJson     Ví dụ JSON response do AI sinh ra (đã được parse và map)
     */
    /**
     * Secure method — verifies {@code requireCanTriggerAiJob} before persisting.
     * Must be called only from HTTP-authenticated request threads.
     */
    void enrichDocumentVersionData(UUID id, String summary, String description, String reqJson, String resJson);

    /**
     * Async-safe internal persistence method for RabbitMQ workers.
     * Does NOT check CurrentUserService or ProjectAccessService.
     * Permission was already verified at HTTP trigger time when the job was created.
     * Must NOT be called from user-facing HTTP endpoints.
     */
    void enrichDocumentVersionDataFromAiJob(UUID id, String summary, String description, String reqJson, String resJson);
}