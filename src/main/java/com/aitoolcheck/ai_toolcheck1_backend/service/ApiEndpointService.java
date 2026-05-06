package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointResponse;

import java.util.List;
import java.util.UUID;

public interface ApiEndpointService {

    List<ApiEndpointResponse> getByProjectId(UUID projectId);

    ApiEndpointDetailResponse getById(UUID id);

    void enrichEndpointData(UUID id, String summary, String description, String reqJson, String resJson);
}
