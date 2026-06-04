package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.common.RuntimeTargetUrlValidator;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req.RegisterExternalRuntimeRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentCapability;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeDetectorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.runtime.RuntimeOrchestratorFactory;
import com.aitoolcheck.ai_toolcheck1_backend.service.runtime.RuntimeOrchestratorStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SourceRuntimeServiceImpl} — Phase 1 Runtime Manager Foundation.
 *
 * <p>Covers:
 * <ul>
 *   <li>EXTERNAL runtime registration + baseUrl validation</li>
 *   <li>stopRuntime: STOPPED persisted, idempotent, no-runtime safe</li>
 *   <li>resolveBaseUrlForTestRun: 3-tier priority (request → UP runtime → project default)</li>
 *   <li>AUTO runtime skeleton: honest statuses, no fake RUNNING</li>
 *   <li>healthCheck: NO_URL guard</li>
 * </ul>
 */
class SourceRuntimeServiceImplTest {

    private SourceRuntimeRepository sourceRuntimeRepository;
    private ProjectAccessService projectAccessService;
    private RuntimeAutoProperties runtimeAutoProperties;
    private RuntimeDetectorService runtimeDetectorService;
    private RuntimeSourceMaterializer runtimeSourceMaterializer;
    private RuntimeOrchestratorFactory orchestratorFactory;
    private RuntimeOrchestratorStrategy orchestratorStrategy;
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
        orchestratorFactory = mock(RuntimeOrchestratorFactory.class);
        orchestratorStrategy = mock(RuntimeOrchestratorStrategy.class);
        when(orchestratorFactory.getStrategy()).thenReturn(orchestratorStrategy);
        when(orchestratorFactory.getLastReport()).thenReturn(null);

        service = new SourceRuntimeServiceImpl(
                sourceRuntimeRepository,
                projectAccessService,
                runtimeAutoProperties,
                runtimeDetectorService,
                runtimeSourceMaterializer,
                orchestratorFactory
        );
        projectId = UUID.randomUUID();
        project = new SourceProject();
        project.setId(projectId);
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.empty());
        when(sourceRuntimeRepository.save(any(SourceRuntime.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectAccessService.requireCanViewProject(projectId)).thenReturn(project);
        when(projectAccessService.requireCanManageProject(projectId)).thenReturn(project);
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
    }

    // ── GET: getCurrentRuntime ─────────────────────────────────────────────────

    @Test
    void getRuntime_whenNoneExists_returnsNotCreated() {
        var response = service.getCurrentRuntime(projectId);

        assertThat(response.getProjectId()).isEqualTo(projectId);
        assertThat(response.getRuntimeStatus()).isEqualTo(RuntimeStatus.NOT_CREATED);
    }

    @Test
    void getRuntime_whenExistsReturnsIt() {
        SourceRuntime existing = buildExternalRuntime(RuntimeStatus.UP, "http://localhost:8081");
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.of(existing));

        var response = service.getCurrentRuntime(projectId);

        assertThat(response.getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(response.getPublicBaseUrl()).isEqualTo("http://localhost:8081");
    }

    // ── EXTERNAL: registerExternalRuntime ─────────────────────────────────────

    @Test
    void registerExternalRuntime_createsRuntimeRecordWithStatusUp() {
        RegisterExternalRuntimeRequest req = RegisterExternalRuntimeRequest.builder()
                .baseUrl("http://localhost:8081")
                .label("local dev")
                .build();

        var response = service.registerExternalRuntime(projectId, req);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_REGISTERED);
        assertThat(response.getRuntime().getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(response.getRuntime().getPublicBaseUrl()).isEqualTo("http://localhost:8081");
        assertThat(response.getRuntime().getRuntimeMode()).isEqualTo(RuntimeMode.EXTERNAL_BASE_URL);

        ArgumentCaptor<SourceRuntime> captor = ArgumentCaptor.forClass(SourceRuntime.class);
        verify(sourceRuntimeRepository).save(captor.capture());
        assertThat(captor.getValue().getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(captor.getValue().getPublicBaseUrl()).isEqualTo("http://localhost:8081");
    }

    @Test
    void registerExternalRuntime_stripsTrailingSlash() {
        RegisterExternalRuntimeRequest req = RegisterExternalRuntimeRequest.builder()
                .baseUrl("http://localhost:8081/")
                .build();

        var response = service.registerExternalRuntime(projectId, req);

        assertThat(response.getRuntime().getPublicBaseUrl()).isEqualTo("http://localhost:8081");
    }

    @Test
    void registerExternalRuntime_stopsExistingUpRuntime() {
        SourceRuntime existing = buildExternalRuntime(RuntimeStatus.UP, "http://old-server:9000");
        when(sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP))
                .thenReturn(Optional.of(existing));

        RegisterExternalRuntimeRequest req = RegisterExternalRuntimeRequest.builder()
                .baseUrl("http://new-server:8080")
                .build();

        service.registerExternalRuntime(projectId, req);

        // The old runtime must be saved with STOPPED
        ArgumentCaptor<SourceRuntime> captor = ArgumentCaptor.forClass(SourceRuntime.class);
        verify(sourceRuntimeRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        // At least one save call with STOPPED (old runtime)
        assertThat(existing.getRuntimeStatus()).isEqualTo(RuntimeStatus.STOPPED);
        assertThat(existing.getStoppedAt()).isNotNull();
        
        // Also verify the new runtime is saved as UP
        List<SourceRuntime> savedRuntimes = captor.getAllValues();
        assertThat(savedRuntimes).hasSize(2);
        assertThat(savedRuntimes.get(0).getRuntimeStatus()).isEqualTo(RuntimeStatus.STOPPED);
        assertThat(savedRuntimes.get(1).getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(savedRuntimes.get(1).getPublicBaseUrl()).isEqualTo("http://new-server:8080");
    }

    @Test
    void registerExternalRuntime_rejectsInvalidScheme() {
        RegisterExternalRuntimeRequest req = RegisterExternalRuntimeRequest.builder()
                .baseUrl("ftp://badscheme.com")
                .build();

        assertThatThrownBy(() -> service.registerExternalRuntime(projectId, req))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid scheme");
    }

    @Test
    void registerExternalRuntime_rejectsBlankUrl() {
        RegisterExternalRuntimeRequest req = RegisterExternalRuntimeRequest.builder()
                .baseUrl("   ")
                .build();

        assertThatThrownBy(() -> service.registerExternalRuntime(projectId, req))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("baseUrl is required");
    }

    @Test
    void registerExternalRuntime_rejectsNullRequest() {
        assertThatThrownBy(() -> service.registerExternalRuntime(projectId, null))
                .isInstanceOf(BadRequestException.class);
    }

    // ── STOP: stopRuntime ─────────────────────────────────────────────────────

    @Test
    void stopRuntime_whenNoneExists_isSafeNoOp() {
        var response = service.stopRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_STOPPED);
        verify(sourceRuntimeRepository, never()).save(any());
    }

    @Test
    void stopRuntime_whenUpRuntime_persistsStoppedWithTimestamp() {
        SourceRuntime existing = buildExternalRuntime(RuntimeStatus.UP, "http://localhost:8081");
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.of(existing));

        var response = service.stopRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_STOPPED);

        ArgumentCaptor<SourceRuntime> captor = ArgumentCaptor.forClass(SourceRuntime.class);
        verify(sourceRuntimeRepository).save(captor.capture());
        assertThat(captor.getValue().getRuntimeStatus()).isEqualTo(RuntimeStatus.STOPPED);
        assertThat(captor.getValue().getStoppedAt()).isNotNull();
    }

    @Test
    void stopRuntime_whenAlreadyStopped_isIdempotent() {
        SourceRuntime existing = buildExternalRuntime(RuntimeStatus.STOPPED, "http://localhost:8081");
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.of(existing));

        var response = service.stopRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_STOPPED);
        // Already stopped — save should NOT be called again
        verify(sourceRuntimeRepository, never()).save(any());
    }

    // ── resolveBaseUrlForTestRun — 3-tier priority ────────────────────────────

    @Test
    void resolveBaseUrlForTestRun_priority1_requestUrlUsedFirst() {
        String resolved = service.resolveBaseUrlForTestRun(
                projectId, "http://request-url:8080", "http://project-default:9090");

        assertThat(resolved).isEqualTo("http://request-url:8080");
        // Must NOT query repository at all
        verifyNoInteractions(sourceRuntimeRepository);
    }

    @Test
    void resolveBaseUrlForTestRun_priority2_upRuntimeUsedWhenNoRequestUrl() {
        SourceRuntime upRuntime = buildExternalRuntime(RuntimeStatus.UP, "http://running-server:8081");
        when(sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP))
                .thenReturn(Optional.of(upRuntime));

        String resolved = service.resolveBaseUrlForTestRun(projectId, null, "http://project-default:9090");

        assertThat(resolved).isEqualTo("http://running-server:8081");
    }

    @Test
    void resolveBaseUrlForTestRun_priority2_blankRequestUrlFallsToRuntime() {
        SourceRuntime upRuntime = buildExternalRuntime(RuntimeStatus.UP, "http://running-server:8081");
        when(sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP))
                .thenReturn(Optional.of(upRuntime));

        String resolved = service.resolveBaseUrlForTestRun(projectId, "   ", "http://project-default:9090");

        assertThat(resolved).isEqualTo("http://running-server:8081");
    }

    @Test
    void resolveBaseUrlForTestRun_priority3_projectDefaultUsedWhenNoRuntimeUp() {
        when(sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP))
                .thenReturn(Optional.empty());

        String resolved = service.resolveBaseUrlForTestRun(
                projectId, null, "http://project-default:9090");

        assertThat(resolved).isEqualTo("http://project-default:9090");
    }

    @Test
    void resolveBaseUrlForTestRun_allNull_throwsClearGuidanceError() {
        when(sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveBaseUrlForTestRun(projectId, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No runtime base URL available")
                .hasMessageContaining("register an External Runtime");
    }

    @Test
    void resolveBaseUrlForTestRun_invalidRequestUrl_rejects() {
        assertThatThrownBy(() -> service.resolveBaseUrlForTestRun(projectId, "not-a-url", null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void resolveBaseUrlForTestRun_requestUrlStripsTrailingSlash() {
        String resolved = service.resolveBaseUrlForTestRun(
                projectId, "http://localhost:8080/", null);

        assertThat(resolved).isEqualTo("http://localhost:8080");
    }

    // ── Health check ──────────────────────────────────────────────────────────

    @Test
    void healthCheckRuntime_whenNoRuntime_throwsResourceNotFound() {
        assertThatThrownBy(() -> service.healthCheckRuntime(projectId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void healthCheckRuntime_whenNoUrl_returnsHealthUnknown() {
        SourceRuntime runtime = buildExternalRuntime(RuntimeStatus.UP, null);
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.of(runtime));

        var response = service.healthCheckRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_HEALTH_UNKNOWN);
        ArgumentCaptor<SourceRuntime> captor = ArgumentCaptor.forClass(SourceRuntime.class);
        verify(sourceRuntimeRepository).save(captor.capture());
        assertThat(captor.getValue().getLastHealthStatus()).isEqualTo("NO_URL");
    }

    // ── AUTO runtime (kept from original tests) ────────────────────────────────

    @Test
    void runtimeProperties_loadDefaults() {
        RuntimeAutoProperties properties = new RuntimeAutoProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getDockerNetwork()).isEqualTo("ai-toolcheck-network");
        assertThat(properties.getInternalPort()).isEqualTo(8080);
        assertThat(properties.getBuildTimeoutSeconds()).isEqualTo(300);
        assertThat(properties.getStartupTimeoutSeconds()).isEqualTo(120);
        assertThat(properties.getPortMin()).isEqualTo(18080);
        assertThat(properties.getPortMax()).isEqualTo(18999);
        assertThat(properties.getContainerPrefix()).isEqualTo("aitc-runtime");
        assertThat(properties.getMaxActiveRuntimes()).isEqualTo(5);
    }

    @Test
    void autoRuntimeDisabled_ensureRuntimeReady_throwsDisabledError() {
        // No UP runtime exists → falls through to AUTO path → disabled
        when(sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureRuntimeReady(projectId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("disabled");

        verifyNoInteractions(runtimeDetectorService);
        verifyNoInteractions(runtimeSourceMaterializer);
    }

    @Test
    void ensureRuntimeReady_whenUpRuntimeExists_returnsItWithoutThrowing() {
        SourceRuntime upRuntime = buildExternalRuntime(RuntimeStatus.UP, "http://localhost:8081");
        when(sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP))
                .thenReturn(Optional.of(upRuntime));

        var response = service.ensureRuntimeReady(projectId);

        assertThat(response.getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(response.getPublicBaseUrl()).isEqualTo("http://localhost:8081");
        verifyNoInteractions(runtimeDetectorService);
    }

    @Test
    void startRuntime_whenAutoDisabled_returnsClearDisabledResponse() {
        var response = service.startRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.AUTO_RUNTIME_DISABLED_CODE);
        assertThat(response.getMessage()).contains("disabled");
        assertThat(response.getRuntime().getRuntimeStatus()).isEqualTo(RuntimeStatus.NOT_CREATED);
    }

    @Test
    void autoRuntimeEnabled_noUpRuntime_ensureRuntimeReady_throwsNoUpRuntimeError() {
        runtimeAutoProperties.setEnabled(true);
        when(sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(projectId, RuntimeStatus.UP))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureRuntimeReady(projectId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No UP runtime found");
    }

    @Test
    void startRuntime_unsupportedSource_savesBuildFailedRuntime() {
        runtimeAutoProperties.setEnabled(true);
        RuntimeDetectionResult detection = RuntimeDetectionResult.builder()
                .supported(false)
                .message("No supported Spring Boot build file found.")
                .build();
        when(runtimeDetectorService.detect(projectId)).thenReturn(detection);

        var response = service.startRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.AUTO_RUNTIME_UNSUPPORTED_CODE);
        ArgumentCaptor<SourceRuntime> captor = ArgumentCaptor.forClass(SourceRuntime.class);
        verify(sourceRuntimeRepository).save(captor.capture());
        assertThat(captor.getValue().getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(captor.getValue().getLastError()).contains("No supported Spring Boot build file found");
    }

    @Test
    void startRuntime_supportedSource_delegatesToOrchestrator() {
        runtimeAutoProperties.setEnabled(true);
        when(runtimeDetectorService.detect(projectId)).thenReturn(supportedMaven());
        SourceRuntime expectedResult = SourceRuntime.builder()
                .sourceProject(project)
                .runtimeStatus(RuntimeStatus.ENVIRONMENT_UNSUPPORTED)
                .lastError("Docker not available")
                .build();
        when(orchestratorStrategy.start(any(), any())).thenReturn(expectedResult);

        var response = service.startRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_ENVIRONMENT_UNSUPPORTED);
        verify(orchestratorStrategy).start(eq(project), any());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private SourceRuntime buildExternalRuntime(RuntimeStatus status, String baseUrl) {
        SourceRuntime rt = new SourceRuntime();
        rt.setId(UUID.randomUUID());
        rt.setSourceProject(project);
        rt.setRuntimeMode(RuntimeMode.EXTERNAL_BASE_URL);
        rt.setRuntimeStatus(status);
        rt.setRuntimeType(RuntimeType.UNKNOWN);
        rt.setPublicBaseUrl(baseUrl);
        rt.setCreatedAt(LocalDateTime.now());
        rt.setUpdatedAt(LocalDateTime.now());
        return rt;
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

    // ── Phase 2: updateHealthCheckPath ─────────────────────────────────────────

    @Test
    void updateHealthCheckPath_valid_updatesAndReturnsSuccess() {
        SourceRuntime runtime = buildExternalRuntime(RuntimeStatus.UP, "http://example.com:8081");
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.of(runtime));

        var response = service.updateHealthCheckPath(projectId, "/greeting");

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_SUCCESS);
        assertThat(runtime.getHealthCheckPath()).isEqualTo("/greeting");
        verify(sourceRuntimeRepository).save(runtime);
    }

    @Test
    void updateHealthCheckPath_blank_throwsBadRequest() {
        assertThatThrownBy(() -> service.updateHealthCheckPath(projectId, ""))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("healthCheckPath must not be blank");
    }

    @Test
    void updateHealthCheckPath_missingLeadingSlash_throwsBadRequest() {
        assertThatThrownBy(() -> service.updateHealthCheckPath(projectId, "greeting"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must start with /");
    }

    @Test
    void updateHealthCheckPath_whenNoRuntime_throwsNotFound() {
        when(sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateHealthCheckPath(projectId, "/greeting"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Phase 2: getEnvironmentCapabilities ───────────────────────────────────

    @Test
    void getEnvironmentCapabilities_returnsCachedReportWhenAvailable() {
        EnvironmentCapabilityReport cached = EnvironmentCapabilityReport.builder()
                .available(EnumSet.of(EnvironmentCapability.WRITABLE_TEMP_DIR))
                .missing(EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET,
                        EnvironmentCapability.JDK, EnvironmentCapability.MAVEN, EnvironmentCapability.GRADLE))
                .summary("UNSUPPORTED: no docker socket, JRE-only.")
                .probedAt(LocalDateTime.now())
                .build();
        when(orchestratorFactory.getLastReport()).thenReturn(cached);

        var result = service.getEnvironmentCapabilities();

        assertThat(result).isSameAs(cached);
        verify(orchestratorFactory, org.mockito.Mockito.never()).reprobeAndSelect();
    }

    @Test
    void getEnvironmentCapabilities_reprobesWhenNoCachedReport() {
        when(orchestratorFactory.getLastReport()).thenReturn(null);
        EnvironmentCapabilityReport fresh = EnvironmentCapabilityReport.builder()
                .available(EnumSet.of(EnvironmentCapability.WRITABLE_TEMP_DIR))
                .missing(EnumSet.of(EnvironmentCapability.DOCKER_CLI))
                .summary("UNSUPPORTED")
                .probedAt(LocalDateTime.now())
                .build();
        when(orchestratorFactory.reprobeAndSelect()).thenReturn(fresh);

        var result = service.getEnvironmentCapabilities();

        assertThat(result).isSameAs(fresh);
        verify(orchestratorFactory).reprobeAndSelect();
    }

    // ── Phase 2: startRuntime with orchestrator ────────────────────────────────

    @Test
    void startRuntime_disabled_returnsDisabledCode() {
        runtimeAutoProperties.setEnabled(false);
        // No need to set up detector — should short-circuit

        var response = service.startRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.AUTO_RUNTIME_DISABLED_CODE);
        verifyNoInteractions(runtimeDetectorService);
        verifyNoInteractions(orchestratorStrategy);
    }

    @Test
    void startRuntime_enabled_unsupportedDetection_returnsBuildFailedCode() {
        runtimeAutoProperties.setEnabled(true);
        RuntimeDetectionResult unsupported = RuntimeDetectionResult.builder()
                .supported(false)
                .message("No pom.xml or build.gradle found.")
                .build();
        when(runtimeDetectorService.detect(projectId)).thenReturn(unsupported);

        var response = service.startRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.AUTO_RUNTIME_UNSUPPORTED_CODE);
        verifyNoInteractions(orchestratorStrategy);
    }

    @Test
    void startRuntime_enabled_orchestratorReturnsEnvironmentUnsupported_returnsCorrectCode() {
        runtimeAutoProperties.setEnabled(true);
        when(runtimeDetectorService.detect(projectId)).thenReturn(supportedMaven());

        SourceRuntime unsupportedRuntime = SourceRuntime.builder()
                .sourceProject(project)
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(RuntimeStatus.ENVIRONMENT_UNSUPPORTED)
                .runtimeType(RuntimeType.SPRING_BOOT_MAVEN)
                .lastError("UNSUPPORTED: Docker socket not mounted.")
                .build();
        when(orchestratorStrategy.start(any(), any())).thenReturn(unsupportedRuntime);

        var response = service.startRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_ENVIRONMENT_UNSUPPORTED);
        assertThat(response.getRuntime().getRuntimeStatus()).isEqualTo(RuntimeStatus.ENVIRONMENT_UNSUPPORTED);
        assertThat(response.getRuntime().getPublicBaseUrl()).isNull();
    }

    @Test
    void startRuntime_enabled_orchestratorReturnsUp_returnsSuccessCode() {
        runtimeAutoProperties.setEnabled(true);
        when(runtimeDetectorService.detect(projectId)).thenReturn(supportedMaven());

        SourceRuntime upRuntime = SourceRuntime.builder()
                .sourceProject(project)
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(RuntimeStatus.UP)
                .runtimeType(RuntimeType.SPRING_BOOT_MAVEN)
                .publicBaseUrl("http://127.0.0.1:18080/api")
                .build();
        when(orchestratorStrategy.start(any(), any())).thenReturn(upRuntime);

        var response = service.startRuntime(projectId);

        assertThat(response.getCode()).isEqualTo(SourceRuntimeServiceImpl.CODE_SUCCESS);
        assertThat(response.getRuntime().getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(response.getRuntime().getPublicBaseUrl()).isEqualTo("http://127.0.0.1:18080/api");
    }
}

