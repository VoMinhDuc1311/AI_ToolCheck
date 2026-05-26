package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiEndpointServiceImplTest {

    @Mock private ApiEndpointRepository apiEndpointRepository;
    @Mock private SourceProjectRepository sourceProjectRepository;
    @Mock private ProjectAccessService projectAccessService;

    private ApiEndpointServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApiEndpointServiceImpl(apiEndpointRepository, sourceProjectRepository, projectAccessService);
    }

    @Test
    void getByProjectIdUsesOnlyActiveNonStaleEndpoints() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        ApiEndpoint clean = new ApiEndpoint();
        clean.setId(UUID.randomUUID());
        clean.setSourceProject(project);
        clean.setEndpointPath("/legacy/orders");
        clean.setControllerName("OrderGateway");
        clean.setHttpMethod(HttpMethod.GET);
        clean.setActiveFlag(true);
        clean.setStaleFlag(false);

        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(clean));

        var result = service.getByProjectId(projectId);

        assertEquals(1, result.size());
        assertEquals("/legacy/orders", result.get(0).getEndpointPath());
        verify(apiEndpointRepository).findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId);
    }
}
