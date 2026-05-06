package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiEndpointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiEndpointServiceImpl implements ApiEndpointService {

    private final ApiEndpointRepository apiEndpointRepository;
    private final SourceProjectRepository sourceProjectRepository;

    @Override
    public List<ApiEndpointResponse> getByProjectId(UUID projectId) {
        if (!sourceProjectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Source project not found with id: " + projectId);
        }

        return apiEndpointRepository.findBySourceProjectId(projectId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public ApiEndpointDetailResponse getById(UUID id) {
        ApiEndpoint apiEndpoint = apiEndpointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API endpoint not found with id: " + id));

        return mapToDetailResponse(apiEndpoint);
    }

    @Override
    @Transactional
    public void enrichEndpointData(UUID id, String summary, String description, String reqJson, String resJson) {
        log.info("[ApiEndpoint] Cập nhật dữ liệu do AI làm giàu cho Endpoint ID: {}", id);
        
        ApiEndpoint endpoint = apiEndpointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API endpoint not found with id: " + id));
                
        endpoint.setSummary(summary);
        endpoint.setDescription(description);
        endpoint.setExampleRequestJson(reqJson);
        endpoint.setExampleResponseJson(resJson);
        endpoint.setAiEnrichedFlag(true);
        
        apiEndpointRepository.save(endpoint);
        log.debug("[ApiEndpoint] Cập nhật thành công cho Endpoint ID: {}", id);
    }

    private ApiEndpointResponse mapToResponse(ApiEndpoint apiEndpoint) {
        return ApiEndpointResponse.builder()
                .id(apiEndpoint.getId())
                .projectId(apiEndpoint.getSourceProject().getId())
                .httpMethod(apiEndpoint.getHttpMethod())
                .endpointPath(apiEndpoint.getEndpointPath())
                .operationId(apiEndpoint.getOperationId())
                .tagName(apiEndpoint.getTagName())
                .authRequired(apiEndpoint.getAuthRequired())
                .deprecatedFlag(apiEndpoint.getDeprecatedFlag())
                .build();
    }

    private ApiEndpointDetailResponse mapToDetailResponse(ApiEndpoint apiEndpoint) {
        return ApiEndpointDetailResponse.builder()
                .id(apiEndpoint.getId())
                .projectId(apiEndpoint.getSourceProject().getId())
                .sourceFileId(apiEndpoint.getSourceFile() == null ? null : apiEndpoint.getSourceFile().getId())
                .controllerName(apiEndpoint.getControllerName())
                .methodName(apiEndpoint.getMethodName())
                .httpMethod(apiEndpoint.getHttpMethod())
                .endpointPath(apiEndpoint.getEndpointPath())
                .operationId(apiEndpoint.getOperationId())
                .tagName(apiEndpoint.getTagName())
                .authRequired(apiEndpoint.getAuthRequired())
                .deprecatedFlag(apiEndpoint.getDeprecatedFlag())
                .createdAt(apiEndpoint.getCreatedAt())
                .updatedAt(apiEndpoint.getUpdatedAt())
                .build();
    }
}
