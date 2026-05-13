package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointResponse;

import java.util.List;
import java.util.UUID;

public interface ApiEndpointService {

    List<ApiEndpointResponse> getByProjectId(UUID projectId);

    ApiEndpointDetailResponse getById(UUID id);

    /**
     * Secure method — verifies {@code requireCanTriggerAiJob} before persisting.
     * Must be called only from HTTP-authenticated request threads.
     */
    void enrichEndpointData(UUID id, String summary, String description, String reqJson, String resJson, String openapiFragmentJson, UUID jobId);

    /**
     * Async-safe internal persistence method for RabbitMQ workers.
     * <p>
     * Does NOT check CurrentUserService or ProjectAccessService.
     * Permission was already verified at HTTP trigger time when the job was created.
     * Must NOT be called from user-facing HTTP endpoints.
     */
    void enrichEndpointDataFromAiJob(UUID id, String summary, String description, String reqJson, String resJson, String openapiFragmentJson, UUID jobId);
}
