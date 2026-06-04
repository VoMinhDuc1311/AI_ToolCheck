package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link UnsupportedRuntimeOrchestrator}.
 *
 * <p>Verifies honest ENVIRONMENT_UNSUPPORTED status reporting when
 * Docker/JDK capabilities are absent.
 */
class UnsupportedRuntimeOrchestratorTest {

    private SourceRuntimeRepository repository;
    private UnsupportedRuntimeOrchestrator orchestrator;
    private SourceProject project;

    @BeforeEach
    void setUp() {
        repository = mock(SourceRuntimeRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator = new UnsupportedRuntimeOrchestrator(
                repository,
                "UNSUPPORTED: Docker socket not mounted, JRE-only image."
        );

        project = mock(SourceProject.class);
        when(project.getId()).thenReturn(java.util.UUID.randomUUID());
    }

    @Test
    void start_setsEnvironmentUnsupportedStatus() {
        RuntimeDetectionResult detection = mock(RuntimeDetectionResult.class);
        when(detection.getRuntimeType()).thenReturn(RuntimeType.SPRING_BOOT_MAVEN);
        when(detection.getDetectedPort()).thenReturn(8080);
        when(detection.getContextPath()).thenReturn("/api");

        SourceRuntime result = orchestrator.start(project, detection);

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.ENVIRONMENT_UNSUPPORTED);
    }

    @Test
    void start_neverSetsPublicBaseUrl() {
        RuntimeDetectionResult detection = mock(RuntimeDetectionResult.class);
        when(detection.getRuntimeType()).thenReturn(RuntimeType.SPRING_BOOT_MAVEN);

        SourceRuntime result = orchestrator.start(project, detection);

        assertThat(result.getPublicBaseUrl()).isNull();
    }

    @Test
    void start_persistsLastErrorWithCapabilitySummary() {
        RuntimeDetectionResult detection = mock(RuntimeDetectionResult.class);
        when(detection.getRuntimeType()).thenReturn(RuntimeType.SPRING_BOOT_GRADLE);

        SourceRuntime result = orchestrator.start(project, detection);

        assertThat(result.getLastError()).isNotBlank();
        assertThat(result.getLastError()).containsIgnoringCase("UNSUPPORTED");
    }

    @Test
    void start_preservesDetectedRuntimeType() {
        RuntimeDetectionResult detection = mock(RuntimeDetectionResult.class);
        when(detection.getRuntimeType()).thenReturn(RuntimeType.SPRING_BOOT_MAVEN);
        when(detection.getDetectedPort()).thenReturn(8081);

        SourceRuntime result = orchestrator.start(project, detection);

        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_MAVEN);
        assertThat(result.getDetectedPort()).isEqualTo(8081);
    }

    @Test
    void start_savesRuntimeToRepository() {
        RuntimeDetectionResult detection = mock(RuntimeDetectionResult.class);
        when(detection.getRuntimeType()).thenReturn(RuntimeType.SPRING_BOOT_MAVEN);

        orchestrator.start(project, detection);

        verify(repository).save(any(SourceRuntime.class));
    }

    @Test
    void stop_setsStoppedStatusAndPersists() {
        SourceRuntime runtime = SourceRuntime.builder()
                .sourceProject(project)
                .runtimeStatus(RuntimeStatus.ENVIRONMENT_UNSUPPORTED)
                .build();

        orchestrator.stop(runtime);

        assertThat(runtime.getRuntimeStatus()).isEqualTo(RuntimeStatus.STOPPED);
        assertThat(runtime.getStoppedAt()).isNotNull();
        verify(repository).save(runtime);
    }

    @Test
    void stop_isIdempotentWhenAlreadyStopped() {
        SourceRuntime runtime = SourceRuntime.builder()
                .sourceProject(project)
                .runtimeStatus(RuntimeStatus.STOPPED)
                .build();

        orchestrator.stop(runtime);

        // Should NOT call save again for an already-stopped runtime
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void name_isHumanReadable() {
        assertThat(orchestrator.name()).isEqualTo("UnsupportedRuntimeOrchestrator");
    }
}
