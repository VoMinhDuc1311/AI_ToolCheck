package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.CreateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.UpdateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.res.ApiDocumentDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocument;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiDocumentService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiDocumentServiceImpl implements ApiDocumentService {

    private final ApiDocumentRepository apiDocumentRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final SourceProjectRepository sourceProjectRepository;

    @Override
    @Transactional
    public ApiDocumentDetailResponse create(CreateApiDocumentRequest request) {
        SourceProject sourceProject = sourceProjectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SourceProject not found with id: " + request.getProjectId()
                ));

        if (apiDocumentRepository.existsBySourceProjectId(request.getProjectId())) {
            throw new BadRequestException(
                    "ApiDocument already exists for project id: " + request.getProjectId()
            );
        }

        String documentName = hasText(request.getDocumentName())
                ? request.getDocumentName().trim()
                : buildDefaultDocumentName(sourceProject);

        String documentType = hasText(request.getDocumentType())
                ? request.getDocumentType().trim()
                : "OPENAPI_3";

        ApiDocument apiDocument = ApiDocument.builder()
                .sourceProject(sourceProject)
                .documentName(documentName)
                .documentType(documentType)
                .currentVersionNo(0)
                .publishedFlag(false)
                .build();

        ApiDocument saved = apiDocumentRepository.save(apiDocument);
        return toDetailResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiDocumentDetailResponse getByProjectId(UUID projectId) {
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
        return toDetailResponse(apiDocument);
    }

    @Override
    @Transactional
    public ApiDocumentDetailResponse update(UUID id, UpdateApiDocumentRequest request) {
        ApiDocument apiDocument = findDocumentOrThrow(id);

        if (hasText(request.getDocumentName())) {
            apiDocument.setDocumentName(request.getDocumentName().trim());
        }

        if (hasText(request.getDocumentType())) {
            apiDocument.setDocumentType(request.getDocumentType().trim());
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

        apiDocument.setPublishedFlag(false);

        ApiDocument saved = apiDocumentRepository.save(apiDocument);
        return toDetailResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiDocumentVersionResponse> getVersions(UUID apiDocumentId) {
        findDocumentOrThrow(apiDocumentId);

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
                .createdAt(apiDocument.getCreatedAt())
                .updatedAt(apiDocument.getUpdatedAt())
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
                .createdAt(version.getCreatedAt())
                .updatedAt(version.getUpdatedAt())
                .build();
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