package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.req.UpdateApiDocumentVersionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiDocumentVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiDocumentVersionServiceImpl implements ApiDocumentVersionService {

    private final ApiDocumentVersionRepository apiDocumentVersionRepository;

    @Override
    @Transactional(readOnly = true)
    public ApiDocumentVersionDetailResponse getById(UUID versionId) {
        ApiDocumentVersion version = findVersionOrThrow(versionId);
        return toDetailResponse(version);
    }

    @Override
    @Transactional
    public ApiDocumentVersionDetailResponse update(UUID versionId, UpdateApiDocumentVersionRequest request) {
        ApiDocumentVersion version = findVersionOrThrow(versionId);

        if (request.getSummary() != null) {
            version.setSummary(request.getSummary().trim());
        }

        if (request.getDescription() != null) {
            version.setDescription(request.getDescription().trim());
        }

        ApiDocumentVersion saved = apiDocumentVersionRepository.save(version);
        return toDetailResponse(saved);
    }

    private ApiDocumentVersion findVersionOrThrow(UUID versionId) {
        return apiDocumentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApiDocumentVersion not found with id: " + versionId
                ));
    }

    private ApiDocumentVersionDetailResponse toDetailResponse(ApiDocumentVersion version) {
        String contentJson = version.getContentJson();

        return ApiDocumentVersionDetailResponse.builder()
                .id(version.getId())
                .apiDocumentId(version.getApiDocument().getId())
                .projectId(version.getApiDocument().getSourceProject().getId())
                .versionNo(version.getVersionNo())
                .summary(version.getSummary())
                .description(version.getDescription())
                .contentJson(version.getContentJson())
                .exampleRequestJson(version.getExampleRequestJson())
                .exampleResponseJson(version.getExampleResponseJson())
                .openapiFragmentJson(version.getOpenapiFragmentJson())
                .aiEnrichedFlag(version.getAiEnrichedFlag())
                .contentLength(contentJson == null ? 0 : contentJson.length())
                .createdAt(version.getCreatedAt())
                .updatedAt(version.getUpdatedAt())
                .build();
    }
}