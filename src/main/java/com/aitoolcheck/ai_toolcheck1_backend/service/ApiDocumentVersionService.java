package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.req.UpdateApiDocumentVersionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionDetailResponse;

import java.util.UUID;

public interface ApiDocumentVersionService {
    ApiDocumentVersionDetailResponse getById(UUID versionId);

    ApiDocumentVersionDetailResponse update(UUID versionId, UpdateApiDocumentVersionRequest request);
}
