package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiParameterRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiSchemaFieldRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiSchemaRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.EndpointSchemaMapRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataCleanupService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenApiGeneratorServiceImplTest {

    @Mock private SourceProjectRepository sourceProjectRepository;
    @Mock private ApiEndpointRepository apiEndpointRepository;
    @Mock private ApiParameterRepository apiParameterRepository;
    @Mock private ApiSchemaRepository apiSchemaRepository;
    @Mock private ApiSchemaFieldRepository apiSchemaFieldRepository;
    @Mock private EndpointSchemaMapRepository endpointSchemaMapRepository;
    @Mock private ApiDocumentRepository apiDocumentRepository;
    @Mock private ApiDocumentVersionRepository apiDocumentVersionRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private ApiMetadataCleanupService apiMetadataCleanupService;

    private OpenApiGeneratorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OpenApiGeneratorServiceImpl(
                new ObjectMapper(),
                sourceProjectRepository,
                apiEndpointRepository,
                apiParameterRepository,
                apiSchemaRepository,
                apiSchemaFieldRepository,
                endpointSchemaMapRepository,
                apiDocumentRepository,
                apiDocumentVersionRepository,
                projectAccessService,
                apiMetadataCleanupService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void openApiGenerationUsesOnlyActiveNonStaleEndpoints() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Legacy Project");

        ApiEndpoint clean = new ApiEndpoint();
        clean.setId(UUID.randomUUID());
        clean.setEndpointPath("/legacy/orders");
        clean.setControllerName("OrderGateway");
        clean.setHttpMethod(HttpMethod.GET);
        clean.setActiveFlag(true);
        clean.setStaleFlag(false);

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(clean));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(clean.getId())).thenReturn(List.of());
        when(endpointSchemaMapRepository.findByApiEndpointId(clean.getId())).thenReturn(List.of());

        Map<String, Object> openApi = service.generateOpenApiJson(projectId);
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");

        assertTrue(paths.containsKey("/legacy/orders"));
        assertFalse(paths.containsKey("/OrderGateway"));
        verify(apiEndpointRepository).findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId);
    }
}
