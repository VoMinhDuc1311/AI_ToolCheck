package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentCapability;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeOrchestratorFactoryTest {

    @Test
    void selectsDockerOrchestratorOnlyWhenCanRunDocker() {
        EnvironmentCapabilityDetector detector = mock(EnvironmentCapabilityDetector.class);
        when(detector.detect()).thenReturn(report(EnumSet.of(
                EnvironmentCapability.DOCKER_CLI,
                EnvironmentCapability.DOCKER_SOCKET,
                EnvironmentCapability.WRITABLE_TEMP_DIR
        )));

        RuntimeOrchestratorFactory factory = new RuntimeOrchestratorFactory(
                detector,
                mock(SourceRuntimeRepository.class),
                mock(ApiEndpointRepository.class),
                mock(RuntimeSourceMaterializer.class),
                enabledDockerProperties()
        );

        factory.reprobeAndSelect();

        assertThat(factory.getStrategy()).isInstanceOf(DockerRuntimeOrchestrator.class);
    }

    @Test
    void selectsUnsupportedOrchestratorWhenCapabilityMissing() {
        EnvironmentCapabilityDetector detector = mock(EnvironmentCapabilityDetector.class);
        when(detector.detect()).thenReturn(report(EnumSet.of(EnvironmentCapability.WRITABLE_TEMP_DIR)));

        RuntimeOrchestratorFactory factory = new RuntimeOrchestratorFactory(
                detector,
                mock(SourceRuntimeRepository.class),
                mock(ApiEndpointRepository.class),
                mock(RuntimeSourceMaterializer.class),
                enabledDockerProperties()
        );

        factory.reprobeAndSelect();

        assertThat(factory.getStrategy()).isInstanceOf(UnsupportedRuntimeOrchestrator.class);
    }

    private EnvironmentCapabilityReport report(EnumSet<EnvironmentCapability> available) {
        EnumSet<EnvironmentCapability> missing = EnumSet.allOf(EnvironmentCapability.class);
        missing.removeAll(available);
        return EnvironmentCapabilityReport.builder()
                .available(available)
                .missing(missing)
                .summary("test")
                .probedAt(LocalDateTime.now())
                .build();
    }

    private RuntimeAutoProperties enabledDockerProperties() {
        RuntimeAutoProperties properties = new RuntimeAutoProperties();
        properties.setEnabled(true);
        properties.getDocker().setEnabled(true);
        return properties;
    }
}
