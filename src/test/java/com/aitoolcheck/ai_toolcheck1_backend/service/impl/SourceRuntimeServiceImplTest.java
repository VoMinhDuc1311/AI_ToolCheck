package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeDetectorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SourceRuntimeServiceImplTest {

    private SourceRuntimeRepository sourceRuntimeRepository;
    private ProjectAccessService projectAccessService;
    private RuntimeAutoProperties runtimeAutoProperties;
    private RuntimeDetectorService runtimeDetectorService;
    private RuntimeSourceMaterializer runtimeSourceMaterializer;
    private SourceRuntimeServiceImpl service;
    private UUID projectId;
    private SourceProject project;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        sourceRuntimeRepository = mock(SourceRuntimeRepository.class);
        projectAccessService = mock(ProjectAccessService.class);
        runtimeAutoProperties = new RuntimeAutoProperties();
        runtimeDetectorService = mock(RuntimeDetectorService.class);
        runtimeSourceMaterializer = mock(RuntimeSourceMaterializer.class);
        service = new SourceRuntimeServiceImpl(
                sourceRuntimeRepository,
                projectAccessService,
                runtimeAutoProperties,
                runtimeDetectorService,
                runtimeSourceMaterializer
        );
        projectId = UUID.randomUUID();
        project = new SourceProject();
        project.setId(projectId);
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.empty());
        when(sourceRuntimeRepository.save(any(SourceRuntime.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(projectAccessService.requireCanViewProject(projectId)).thenReturn(project);
        when(projectAccessService.requireCanManageProject(projectId)).thenReturn(project);
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
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

    @Test
    void autoRuntimeDisabled_returnsClearDisabledError_withoutDetecting() {
        assertThatThrownBy(() -> service.ensureRuntimeReady(projectId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("disabled");

        verifyNoInteractions(runtimeDetectorService);
        verifyNoInteractions(runtimeSourceMaterializer);
    }

    @Test
    void autoRuntimeEnabled_supportedMaven_detectsAndMaterializesButDoesNotBuildDocker() throws Exception {
        runtimeAutoProperties.setEnabled(true);
        Path root = Files.createDirectories(tempDir.resolve("runtime-source"));
        when(runtimeDetectorService.detect(projectId)).thenReturn(supportedMaven());
        when(runtimeSourceMaterializer.materialize(projectId)).thenReturn(MaterializedRuntimeSource.builder()
                .projectId(projectId)
                .rootDir(root)
                .materializedFiles(java.util.List.of("pom.xml"))
                .build());

        assertThatThrownBy(() -> service.ensureRuntimeReady(projectId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Docker runtime build/start is not implemented yet");

        verify(runtimeDetectorService).detect(projectId);
        verify(runtimeSourceMaterializer).materialize(projectId);
        verify(sourceRuntimeRepository).save(any(SourceRuntime.class));
    }

    @Test
    void autoRuntimeEnabled_unsupportedSource_persistsLastError() {
        runtimeAutoProperties.setEnabled(true);
        when(runtimeDetectorService.detect(projectId)).thenReturn(RuntimeDetectionResult.builder()
                .runtimeType(RuntimeType.UNSUPPORTED)
                .supported(false)
                .message("No supported Spring Boot build file found.")
                .build());

        assertThatThrownBy(() -> service.ensureRuntimeReady(projectId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Auto runtime cannot start this source");

        org.mockito.ArgumentCaptor<SourceRuntime> captor = org.mockito.ArgumentCaptor.forClass(SourceRuntime.class);
        verify(sourceRuntimeRepository).save(captor.capture());
        assertThat(captor.getValue().getRuntimeType()).isEqualTo(RuntimeType.UNSUPPORTED);
        assertThat(captor.getValue().getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(captor.getValue().getLastError()).contains("No supported Spring Boot build file found");
        verify(runtimeSourceMaterializer, never()).materialize(any());
    }

    @Test
    void autoRuntimeEnabled_supportedSource_doesNotMarkRuntimeUp() throws Exception {
        runtimeAutoProperties.setEnabled(true);
        Path root = Files.createDirectories(tempDir.resolve("runtime-source"));
        when(runtimeDetectorService.detect(projectId)).thenReturn(supportedMaven());
        when(runtimeSourceMaterializer.materialize(projectId)).thenReturn(MaterializedRuntimeSource.builder()
                .projectId(projectId)
                .rootDir(root)
                .materializedFiles(java.util.List.of("pom.xml"))
                .build());

        assertThatThrownBy(() -> service.ensureRuntimeReady(projectId))
                .isInstanceOf(BadRequestException.class);

        org.mockito.ArgumentCaptor<SourceRuntime> captor = org.mockito.ArgumentCaptor.forClass(SourceRuntime.class);
        verify(sourceRuntimeRepository).save(captor.capture());
        assertThat(captor.getValue().getRuntimeStatus()).isNotEqualTo(RuntimeStatus.UP);
        assertThat(captor.getValue().getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(captor.getValue().getInternalBaseUrl()).isNull();
        assertThat(captor.getValue().getPublicBaseUrl()).isNull();
    }

    @Test
    void autoRuntimeEnabled_supportedSource_returnsNotImplemented() throws Exception {
        runtimeAutoProperties.setEnabled(true);
        Path root = Files.createDirectories(tempDir.resolve("runtime-source"));
        when(runtimeDetectorService.detect(projectId)).thenReturn(supportedMaven());
        when(runtimeSourceMaterializer.materialize(projectId)).thenReturn(MaterializedRuntimeSource.builder()
                .projectId(projectId)
                .rootDir(root)
                .materializedFiles(java.util.List.of("pom.xml"))
                .build());

        assertThatThrownBy(() -> service.ensureRuntimeReady(projectId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not implemented yet");
    }

    @Test
    void autoRuntimeEnabled_supportedSource_cleansMaterializedTempDir() throws Exception {
        runtimeAutoProperties.setEnabled(true);
        Path root = Files.createDirectories(tempDir.resolve("runtime-source"));
        Files.writeString(root.resolve("pom.xml"), "spring-boot-starter");
        when(runtimeDetectorService.detect(projectId)).thenReturn(supportedMaven());
        when(runtimeSourceMaterializer.materialize(projectId)).thenReturn(MaterializedRuntimeSource.builder()
                .projectId(projectId)
                .rootDir(root)
                .materializedFiles(java.util.List.of("pom.xml"))
                .build());

        assertThatThrownBy(() -> service.ensureRuntimeReady(projectId))
                .isInstanceOf(BadRequestException.class);

        assertThat(Files.exists(root)).isFalse();
    }

    private RuntimeDetectionResult supportedMaven() {
        return RuntimeDetectionResult.builder()
                .runtimeType(RuntimeType.SPRING_BOOT_MAVEN)
                .supported(true)
                .message("Spring Boot Maven project detected.")
                .detectedPort(8081)
                .contextPath("/api")
                .buildFilePath("pom.xml")
                .configFilePath("src/main/resources/application.yml")
                .build();
    }
}
