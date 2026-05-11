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
    // HÀM CẬP NHẬT TỪ AI CONSUMER (AI SKILL 1)
    // =====================================================================
    @Override
    @Transactional
    public void enrichDocumentVersionData(UUID id, String summary, String description, String reqJson, String resJson) {
        log.info("[ApiDocumentVersion] Đang cập nhật dữ liệu do AI làm giàu cho DocumentVersion ID: {}", id);

        // 1. Tìm bản ghi trong bảng (Tái sử dụng hàm findVersionOrThrow để code chuẩn
        // DRY)
        ApiDocumentVersion docVersion = findVersionOrThrow(id);
        projectAccessService.requireCanTriggerAiJob(docVersion.getApiDocument().getSourceProject().getId());

        // 2. Cập nhật 2 trường AI trả về (summary, description)
        docVersion.setSummary(summary);
        docVersion.setDescription(description);

        // 3. Bật cờ AI (Đánh dấu tài liệu đã được máy học can thiệp)
        // Cờ này cực kỳ quan trọng để hệ thống biết tài liệu này đủ điều kiện sinh
        // TestCase ở Tuần 7
        docVersion.setAiEnrichedFlag(true);

        // 4. Lưu lại Database
        apiDocumentVersionRepository.save(docVersion);

        log.debug("[ApiDocumentVersion] Cập nhật thành công cho DocumentVersion ID: {}", id);
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
