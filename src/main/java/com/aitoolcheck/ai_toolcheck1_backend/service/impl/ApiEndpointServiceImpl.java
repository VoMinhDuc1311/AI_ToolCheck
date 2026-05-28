package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiEndpointService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiEndpointServiceImpl implements ApiEndpointService {

    private final ApiEndpointRepository apiEndpointRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional(readOnly = true)
    public List<ApiEndpointResponse> getByProjectId(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);

        return apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ApiEndpointDetailResponse getById(UUID id) {
        ApiEndpoint apiEndpoint = apiEndpointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API endpoint not found with id: " + id));
        projectAccessService.requireCanViewProject(apiEndpoint.getSourceProject().getId());

        return mapToDetailResponse(apiEndpoint);
    }

    @Override
    @Transactional
    public void enrichEndpointData(UUID id, String summary, String description, String reqJson, String resJson, String openapiFragmentJson, UUID jobId) {
        log.info("[ApiEndpoint] Cập nhật dữ liệu do AI làm giàu cho Endpoint ID: {}", id);

        ApiEndpoint endpoint = apiEndpointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API endpoint not found with id: " + id));
        projectAccessService.requireCanTriggerAiJob(endpoint.getSourceProject().getId());

        applyEnrichmentFields(endpoint, summary, description, reqJson, resJson, openapiFragmentJson, jobId);
        apiEndpointRepository.save(endpoint);
        log.debug("[ApiEndpoint] Cập nhật thành công (HTTP path) cho Endpoint ID: {}", id);
    }

    /**
     * Async-safe internal persistence method for RabbitMQ workers.
     * Does NOT call CurrentUserService or ProjectAccessService.
     * Permission was already verified at HTTP trigger time.
     */
    @Override
    @Transactional
    public void enrichEndpointDataFromAiJob(UUID id, String summary, String description, String reqJson, String resJson, String openapiFragmentJson, UUID jobId) {
        log.info("[ApiEndpoint][AsyncJob] Cập nhật dữ liệu do AI làm giàu cho Endpoint ID: {}", id);

        ApiEndpoint endpoint = apiEndpointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API endpoint not found with id: " + id));

        applyEnrichmentFields(endpoint, summary, description, reqJson, resJson, openapiFragmentJson, jobId);
        apiEndpointRepository.save(endpoint);
        log.debug("[ApiEndpoint][AsyncJob] Cập nhật thành công cho Endpoint ID: {}", id);
    }

    /** Shared field-update logic — no auth, no side effects. */
    private void applyEnrichmentFields(ApiEndpoint endpoint, String summary, String description,
            String reqJson, String resJson, String openapiFragmentJson, UUID jobId) {
        endpoint.setAiSummary(summary);
        endpoint.setAiDescription(description);
        endpoint.setExampleRequestJson(reqJson);
        endpoint.setExampleResponseJson(resJson);
        endpoint.setOpenapiFragmentJson(openapiFragmentJson);
        endpoint.setAiEnrichedAt(LocalDateTime.now());
        endpoint.setLastAiJobLogId(jobId);
        endpoint.setAiEnrichedFlag(true);
    }

    private ApiEndpointResponse mapToResponse(ApiEndpoint apiEndpoint) {
        return ApiEndpointResponse.builder()
                .id(apiEndpoint.getId())
                .projectId(apiEndpoint.getSourceProject().getId())
                .sourceFileId(apiEndpoint.getSourceFile() == null ? null : apiEndpoint.getSourceFile().getId())
                .sourceUploadVersionId(apiEndpoint.getSourceUploadVersion() == null ? null : apiEndpoint.getSourceUploadVersion().getId())
                .controllerName(apiEndpoint.getControllerName())
                .methodName(apiEndpoint.getMethodName())
                .httpMethod(apiEndpoint.getHttpMethod())
                .endpointPath(apiEndpoint.getEndpointPath())
                .stableKey(apiEndpoint.getStableKey())
                .description(apiEndpoint.getDescription())
                .operationId(apiEndpoint.getOperationId())
                .tagName(apiEndpoint.getTagName())
                .authRequired(apiEndpoint.getAuthRequired())
                .deprecatedFlag(apiEndpoint.getDeprecatedFlag())
                .activeFlag(apiEndpoint.getActiveFlag())
                .staleFlag(apiEndpoint.getStaleFlag())
                .aiEnrichedFlag(apiEndpoint.getAiEnrichedFlag())
                .aiSummary(apiEndpoint.getAiSummary())
                .aiDescription(apiEndpoint.getAiDescription())
                .aiEnrichedAt(apiEndpoint.getAiEnrichedAt())
                .build();
    }

    private ApiEndpointDetailResponse mapToDetailResponse(ApiEndpoint apiEndpoint) {
        return ApiEndpointDetailResponse.builder()
                .id(apiEndpoint.getId())
                .projectId(apiEndpoint.getSourceProject().getId())
                .sourceFileId(apiEndpoint.getSourceFile() == null ? null : apiEndpoint.getSourceFile().getId())
                .sourceUploadVersionId(apiEndpoint.getSourceUploadVersion() == null ? null : apiEndpoint.getSourceUploadVersion().getId())
                .controllerName(apiEndpoint.getControllerName())
                .methodName(apiEndpoint.getMethodName())
                .httpMethod(apiEndpoint.getHttpMethod())
                .endpointPath(apiEndpoint.getEndpointPath())
                .stableKey(apiEndpoint.getStableKey())
                .description(apiEndpoint.getDescription())
                .operationId(apiEndpoint.getOperationId())
                .tagName(apiEndpoint.getTagName())
                .authRequired(apiEndpoint.getAuthRequired())
                .deprecatedFlag(apiEndpoint.getDeprecatedFlag())
                .activeFlag(apiEndpoint.getActiveFlag())
                .staleFlag(apiEndpoint.getStaleFlag())
                .createdAt(apiEndpoint.getCreatedAt())
                .updatedAt(apiEndpoint.getUpdatedAt())
                .aiEnrichedFlag(apiEndpoint.getAiEnrichedFlag())
                .aiSummary(apiEndpoint.getAiSummary())
                .aiDescription(apiEndpoint.getAiDescription())
                .exampleRequestJson(apiEndpoint.getExampleRequestJson())
                .exampleResponseJson(apiEndpoint.getExampleResponseJson())
                .openapiFragmentJson(apiEndpoint.getOpenapiFragmentJson())
                .aiEnrichedAt(apiEndpoint.getAiEnrichedAt())
                .lastAiJobLogId(apiEndpoint.getLastAiJobLogId())
                .build();
    }
}
