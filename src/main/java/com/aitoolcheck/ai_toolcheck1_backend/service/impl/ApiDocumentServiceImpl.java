package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.CreateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.UpdateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.res.ApiDocumentDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DocumentType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocument;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiDocumentService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiDocumentServiceImpl implements ApiDocumentService {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ApiDocumentRepository apiDocumentRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional
    public ApiDocumentDetailResponse create(CreateApiDocumentRequest request) {
        SourceProject sourceProject = projectAccessService.requireCanGenerateDocs(request.getProjectId());

        if (apiDocumentRepository.existsBySourceProjectId(request.getProjectId())) {
            throw new BadRequestException(
                    "ApiDocument already exists for project id: " + request.getProjectId()
            );
        }

        String documentName = hasText(request.getDocumentName())
                ? request.getDocumentName().trim()
                : buildDefaultDocumentName(sourceProject);

        DocumentType documentType = request.getDocumentType() != null
                ? request.getDocumentType()
                : DocumentType.OPENAPI_3;

        ApiDocument apiDocument = ApiDocument.builder()
                .sourceProject(sourceProject)
                .documentName(documentName)
                .documentType(documentType)
                .currentVersionNo(0)
                .publishedFlag(false)
                .staleFlag(false)
                .build();

        ApiDocument saved = apiDocumentRepository.save(apiDocument);
        return toDetailResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiDocumentDetailResponse getByProjectId(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);

        ApiDocument apiDocument = apiDocumentRepository.findBySourceProjectId(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApiDocument not found for project id: " + projectId
                ));

        return toDetailResponse(apiDocument);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiDocumentDetailResponse getById(UUID id) {
        ApiDocument apiDocument = findDocumentOrThrow(id);
        projectAccessService.requireCanViewProject(apiDocument.getSourceProject().getId());

        return toDetailResponse(apiDocument);
    }

    @Override
    @Transactional
    public ApiDocumentDetailResponse update(UUID id, UpdateApiDocumentRequest request) {
        ApiDocument apiDocument = findDocumentOrThrow(id);
        projectAccessService.requireCanGenerateDocs(apiDocument.getSourceProject().getId());

        if (hasText(request.getDocumentName())) {
            apiDocument.setDocumentName(request.getDocumentName().trim());
        }

        if (request.getDocumentType() != null) {
            apiDocument.setDocumentType(request.getDocumentType());
        }

        if (request.getCurrentVersionNo() != null) {
            validateVersionBelongsToDocument(id, request.getCurrentVersionNo());
            apiDocument.setCurrentVersionNo(request.getCurrentVersionNo());
        }

        ApiDocument saved = apiDocumentRepository.save(apiDocument);
        return toDetailResponse(saved);
    }

    @Override
    @Transactional
    public ApiDocumentDetailResponse publish(UUID id) {
        ApiDocument apiDocument = findDocumentOrThrow(id);
        projectAccessService.requireCanAdminProject(apiDocument.getSourceProject().getId());

        if (apiDocument.getCurrentVersionNo() == null || apiDocument.getCurrentVersionNo() <= 0) {
            throw new BadRequestException("Cannot publish ApiDocument without a generated version.");
        }

        validateVersionBelongsToDocument(id, apiDocument.getCurrentVersionNo());

        apiDocument.setPublishedFlag(true);

        ApiDocument saved = apiDocumentRepository.save(apiDocument);
        return toDetailResponse(saved);
    }

    @Override
    @Transactional
    public ApiDocumentDetailResponse unpublish(UUID id) {
        ApiDocument apiDocument = findDocumentOrThrow(id);
        projectAccessService.requireCanAdminProject(apiDocument.getSourceProject().getId());

        apiDocument.setPublishedFlag(false);

        ApiDocument saved = apiDocumentRepository.save(apiDocument);
        return toDetailResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiDocumentVersionResponse> getVersions(UUID apiDocumentId) {
        ApiDocument apiDocument = findDocumentOrThrow(apiDocumentId);
        projectAccessService.requireCanViewProject(apiDocument.getSourceProject().getId());

        return apiDocumentVersionRepository
                .findByApiDocumentIdOrderByVersionNoDesc(apiDocumentId)
                .stream()
                .map(this::toVersionResponse)
                .toList();
    }

    private ApiDocument findDocumentOrThrow(UUID id) {
        return apiDocumentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApiDocument not found with id: " + id
                ));
    }

    private void validateVersionBelongsToDocument(UUID apiDocumentId, Integer versionNo) {
        boolean exists = apiDocumentVersionRepository
                .existsByApiDocumentIdAndVersionNo(apiDocumentId, versionNo);

        if (!exists) {
            throw new BadRequestException(
                    "Version " + versionNo + " does not exist for ApiDocument id: " + apiDocumentId
            );
        }
    }

    private ApiDocumentDetailResponse toDetailResponse(ApiDocument apiDocument) {
        return ApiDocumentDetailResponse.builder()
                .id(apiDocument.getId())
                .projectId(apiDocument.getSourceProject().getId())
                .documentName(apiDocument.getDocumentName())
                .documentType(apiDocument.getDocumentType())
                .currentVersionNo(apiDocument.getCurrentVersionNo())
                .publishedFlag(apiDocument.getPublishedFlag())
                .staleFlag(apiDocument.getStaleFlag())
                .createdAt(toVietnamOffsetDateTime(apiDocument.getCreatedAt()))
                .updatedAt(toVietnamOffsetDateTime(apiDocument.getUpdatedAt()))
                .build();
    }

    private ApiDocumentVersionResponse toVersionResponse(ApiDocumentVersion version) {
        String contentJson = version.getContentJson();

        return ApiDocumentVersionResponse.builder()
                .id(version.getId())
                .apiDocumentId(version.getApiDocument().getId())
                .projectId(version.getApiDocument().getSourceProject().getId())
                .versionNo(version.getVersionNo())
                .summary(version.getSummary())
                .description(version.getDescription())
                .aiEnrichedFlag(version.getAiEnrichedFlag())
                .contentLength(contentJson == null ? 0 : contentJson.length())
                .createdAt(toVietnamOffsetDateTime(version.getCreatedAt()))
                .updatedAt(toVietnamOffsetDateTime(version.getUpdatedAt()))
                .build();
    }

    /**
     * Database stores LocalDateTime as UTC.
     * API response returns explicit Asia/Ho_Chi_Minh timezone offset.
     *
     * Example:
     * DB: 2026-05-26 01:26:38
     * API: 2026-05-26T08:26:38+07:00
     */
    private OffsetDateTime toVietnamOffsetDateTime(LocalDateTime value) {
        if (value == null) {
            return null;
        }

        return value
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(DISPLAY_ZONE)
                .toOffsetDateTime();
    }

    private String buildDefaultDocumentName(SourceProject sourceProject) {
        if (hasText(sourceProject.getProjectName())) {
            return sourceProject.getProjectName().trim() + " OpenAPI";
        }

        return "Project OpenAPI";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}