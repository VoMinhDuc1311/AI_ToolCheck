package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.req.UpdateApiDocumentVersionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiDocumentVersionService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiDocumentVersionServiceImpl implements ApiDocumentVersionService {

    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional(readOnly = true)
    public ApiDocumentVersionDetailResponse getById(UUID versionId) {
        ApiDocumentVersion version = findVersionOrThrow(versionId);
        projectAccessService.requireCanViewProject(version.getApiDocument().getSourceProject().getId());
        return toDetailResponse(version);
    }

    @Override
    @Transactional
    public ApiDocumentVersionDetailResponse update(UUID versionId, UpdateApiDocumentVersionRequest request) {
        ApiDocumentVersion version = findVersionOrThrow(versionId);
        projectAccessService.requireCanGenerateDocs(version.getApiDocument().getSourceProject().getId());

        if (request.getSummary() != null) {
            version.setSummary(request.getSummary().trim());
        }

        if (request.getDescription() != null) {
            version.setDescription(request.getDescription().trim());
        }

        ApiDocumentVersion saved = apiDocumentVersionRepository.save(version);
        return toDetailResponse(saved);
    }

    // =====================================================================
    // HÀM CẬP NHẬT TỪ AI CONSUMER (AI SKILL 1) — BẢO MẬT (HTTP thread)
    // =====================================================================
    @Override
    @Transactional
    public void enrichDocumentVersionData(UUID id, String summary, String description, String reqJson, String resJson) {
        log.info("[ApiDocumentVersion] Đang cập nhật dữ liệu do AI làm giàu cho DocumentVersion ID: {}", id);

        ApiDocumentVersion docVersion = findVersionOrThrow(id);
        projectAccessService.requireCanTriggerAiJob(docVersion.getApiDocument().getSourceProject().getId());

        applyVersionEnrichmentFields(docVersion, summary, description);
        apiDocumentVersionRepository.save(docVersion);

        log.debug("[ApiDocumentVersion] Cập nhật thành công (HTTP path) cho DocumentVersion ID: {}", id);
    }

    // =====================================================================
    // HÀM CẬP NHẬT TỪ AI CONSUMER (AI SKILL 1) — ASYNC-SAFE (RabbitMQ thread)
    // =====================================================================
    /**
     * Async-safe internal persistence method for RabbitMQ workers.
     * Does NOT call CurrentUserService or ProjectAccessService.
     * Permission was already verified at HTTP trigger time.
     */
    @Override
    @Transactional
    public void enrichDocumentVersionDataFromAiJob(UUID id, String summary, String description, String reqJson, String resJson) {
        log.info("[ApiDocumentVersion][AsyncJob] Đang cập nhật dữ liệu do AI làm giàu cho DocumentVersion ID: {}", id);

        ApiDocumentVersion docVersion = findVersionOrThrow(id);

        applyVersionEnrichmentFields(docVersion, summary, description);
        apiDocumentVersionRepository.save(docVersion);

        log.debug("[ApiDocumentVersion][AsyncJob] Cập nhật thành công cho DocumentVersion ID: {}", id);
    }

    /** Shared field-update logic — no auth, no side effects. */
    private void applyVersionEnrichmentFields(ApiDocumentVersion docVersion, String summary, String description) {
        docVersion.setSummary(summary);
        docVersion.setDescription(description);
        docVersion.setAiEnrichedFlag(true);
    }

    // =====================================================================
    // HÀM TIỆN ÍCH (UTILITIES)
    // =====================================================================
    private ApiDocumentVersion findVersionOrThrow(UUID versionId) {
        return apiDocumentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApiDocumentVersion not found with id: " + versionId));
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
                .openapiFragmentJson(version.getOpenapiFragmentJson())
                .aiEnrichedFlag(version.getAiEnrichedFlag())
                .contentLength(contentJson == null ? 0 : contentJson.length())
                .createdAt(version.getCreatedAt())
                .updatedAt(version.getUpdatedAt())
                .build();
    }
}
