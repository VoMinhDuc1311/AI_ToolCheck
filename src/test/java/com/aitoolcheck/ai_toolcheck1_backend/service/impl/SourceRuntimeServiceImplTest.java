package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceRuntimeServiceImplTest {

    private SourceRuntimeRepository sourceRuntimeRepository;
    private SourceRuntimeServiceImpl service;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        sourceRuntimeRepository = mock(SourceRuntimeRepository.class);
        service = new SourceRuntimeServiceImpl(
                sourceRuntimeRepository,
                mock(ProjectAccessService.class),
                new RuntimeAutoProperties()
        );
        projectId = UUID.randomUUID();
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.empty());
    }

    @Test
    void getRuntime_whenNoneExists_returnsNotCreated() {
        var response = service.getCurrentRuntime(projectId);

        assertThat(response.getProjectId()).isEqualTo(projectId);
        assertThat(response.getRuntimeStatus()).isEqualTo(RuntimeStatus.NOT_CREATED);
    }

    @Test
    void startRuntime_whenAutoDisabled_returnsClearDisabledResponse() {
        var response = service.startRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.AUTO_RUNTIME_DISABLED_CODE);
        assertThat(response.getMessage()).contains("disabled");
        assertThat(response.getRuntime().getRuntimeStatus()).isEqualTo(RuntimeStatus.NOT_CREATED);
    }

    @Test
    void stopRuntime_whenNoneExists_isSafe() {
        var response = service.stopRuntime(projectId);

        assertThat(response.getCode()).isEqualTo("SUCCESS");
        assertThat(response.getRuntime().getRuntimeStatus()).isEqualTo(RuntimeStatus.STOPPED);
    }

    @Test
    void runtimeProperties_loadDefaults() {
        RuntimeAutoProperties properties = new RuntimeAutoProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getDockerNetwork()).isEqualTo("ai-toolcheck-runtime");
        assertThat(properties.getInternalPort()).isEqualTo(8080);
        assertThat(properties.getBuildTimeoutSeconds()).isEqualTo(300);
        assertThat(properties.getStartupTimeoutSeconds()).isEqualTo(120);
        assertThat(properties.getMaxActiveRuntimes()).isEqualTo(5);
    }
}
