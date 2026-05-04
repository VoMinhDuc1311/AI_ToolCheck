package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.CreateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.UpdateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.res.ApiDocumentDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionResponse;

import java.util.List;
import java.util.UUID;

public interface ApiDocumentService {
    ApiDocumentDetailResponse create(CreateApiDocumentRequest request);

    ApiDocumentDetailResponse getByProjectId(UUID projectId);

    ApiDocumentDetailResponse getById(UUID id);

    ApiDocumentDetailResponse update(UUID id, UpdateApiDocumentRequest request);

    ApiDocumentDetailResponse publish(UUID id);

    ApiDocumentDetailResponse unpublish(UUID id);

    List<ApiDocumentVersionResponse> getVersions(UUID apiDocumentId);
}
