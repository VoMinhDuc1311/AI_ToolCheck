package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeDetectorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SourceRuntimeServiceImpl implements SourceRuntimeService {

    public static final String AUTO_RUNTIME_DISABLED_CODE = "AUTO_RUNTIME_DISABLED";
    public static final String AUTO_RUNTIME_DISABLED_MESSAGE =
            "Auto runtime from uploaded source is disabled. Use External Base URL.";
    public static final String AUTO_RUNTIME_NOT_IMPLEMENTED_CODE = "AUTO_RUNTIME_NOT_IMPLEMENTED";
    public static final String AUTO_RUNTIME_NOT_IMPLEMENTED_MESSAGE =
            "Auto runtime from uploaded source is not enabled yet.";
    public static final String AUTO_RUNTIME_UNSUPPORTED_CODE = "AUTO_RUNTIME_UNSUPPORTED";
    public static final String AUTO_RUNTIME_PHASE_2_READY_MESSAGE =
            "Auto runtime detection/materialization succeeded, but Docker runtime build/start is not implemented yet.";

    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final ProjectAccessService projectAccessService;
    private final RuntimeAutoProperties runtimeAutoProperties;
    private final RuntimeDetectorService runtimeDetectorService;
    private final RuntimeSourceMaterializer runtimeSourceMaterializer;

    @Override
    @Transactional(readOnly = true)
    public SourceRuntimeResponse getCurrentRuntime(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);
        return sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .map(this::toResponse)
                .orElseGet(() -> notCreatedResponse(projectId));
    }

    @Override
    @Transactional
    public SourceRuntimeResponse ensureRuntimeReady(UUID projectId) {
        SourceProject sourceProject = projectAccessService.requireCanCreateTestRun(projectId);
        if (!runtimeAutoProperties.isEnabled()) {
            throw new BadRequestException(AUTO_RUNTIME_DISABLED_MESSAGE);
        }
        processAutoRuntimePhase2(sourceProject);
        throw new BadRequestException(AUTO_RUNTIME_PHASE_2_READY_MESSAGE);
    }

    @Override
    @Transactional
    public RuntimeActionResponse startRuntime(UUID projectId) {
        SourceProject sourceProject = projectAccessService.requireCanManageProject(projectId);
        return runPhase2Action(sourceProject);
    }

    @Override
    @Transactional
    public RuntimeActionResponse rebuildRuntime(UUID projectId) {
        SourceProject sourceProject = projectAccessService.requireCanManageProject(projectId);
        return runPhase2Action(sourceProject);
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeActionResponse stopRuntime(UUID projectId) {
        projectAccessService.requireCanManageProject(projectId);
        SourceRuntimeResponse runtime = getCurrentRuntime(projectId);
        if (runtime.getId() == null) {
            runtime.setRuntimeStatus(RuntimeStatus.STOPPED);
        }
        return RuntimeActionResponse.builder()
                .code("SUCCESS")
                .message("Runtime is not running.")
                .runtime(runtime)
                .build();
    }

    private RuntimeActionResponse runPhase2Action(SourceProject sourceProject) {
        UUID projectId = sourceProject == null ? null : sourceProject.getId();
        if (!runtimeAutoProperties.isEnabled()) {
            return RuntimeActionResponse.builder()
                    .code(AUTO_RUNTIME_DISABLED_CODE)
                    .message(AUTO_RUNTIME_DISABLED_MESSAGE)
                    .runtime(currentOrNotCreated(projectId))
                    .build();
        }

        try {
            processAutoRuntimePhase2(sourceProject);
            return RuntimeActionResponse.builder()
                    .code(AUTO_RUNTIME_NOT_IMPLEMENTED_CODE)
                    .message(AUTO_RUNTIME_PHASE_2_READY_MESSAGE)
                    .runtime(currentOrNotCreated(projectId))
                    .build();
        } catch (BadRequestException ex) {
            String code = ex.getMessage() != null && ex.getMessage().startsWith("Auto runtime cannot start this source:")
                    ? AUTO_RUNTIME_UNSUPPORTED_CODE
                    : AUTO_RUNTIME_NOT_IMPLEMENTED_CODE;
            return RuntimeActionResponse.builder()
                    .code(code)
                    .message(ex.getMessage())
                    .runtime(currentOrNotCreated(projectId))
                    .build();
        }
    }

    private SourceRuntimeResponse currentOrNotCreated(UUID projectId) {
        SourceRuntimeResponse runtime = sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .map(this::toResponse)
                .orElseGet(() -> notCreatedResponse(projectId));
        return runtime;
    }

    private void processAutoRuntimePhase2(SourceProject sourceProject) {
        UUID projectId = sourceProject.getId();
        RuntimeDetectionResult detection = runtimeDetectorService.detect(projectId);
        if (detection == null || !detection.isSupported()) {
            String reason = detection == null ? "Runtime detector returned no result." : detection.getMessage();
            SourceRuntime runtime = upsertRuntime(sourceProject, detection, RuntimeStatus.BUILD_FAILED,
                    reason == null ? "Unsupported runtime source." : reason);
            sourceRuntimeRepository.save(runtime);
            throw new BadRequestException("Auto runtime cannot start this source: " + runtime.getLastError());
        }

        SourceRuntime runtime;
        try (MaterializedRuntimeSource materialized = runtimeSourceMaterializer.materialize(projectId)) {
            runtime = upsertRuntime(sourceProject, detection, RuntimeStatus.BUILD_FAILED,
                    AUTO_RUNTIME_PHASE_2_READY_MESSAGE);
        }
        sourceRuntimeRepository.save(runtime);
        throw new BadRequestException(AUTO_RUNTIME_PHASE_2_READY_MESSAGE);
    }

    private SourceRuntime upsertRuntime(SourceProject sourceProject, RuntimeDetectionResult detection,
                                        RuntimeStatus status, String lastError) {
        SourceRuntime runtime = sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(sourceProject.getId())
                .orElseGet(() -> SourceRuntime.builder()
                        .sourceProject(sourceProject)
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .build());

        runtime.setSourceProject(sourceProject);
        runtime.setRuntimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE);
        runtime.setRuntimeStatus(status);
        runtime.setRuntimeType(detection == null || detection.getRuntimeType() == null
                ? RuntimeType.UNKNOWN
                : detection.getRuntimeType());
        runtime.setDetectedPort(detection == null ? null : detection.getDetectedPort());
        runtime.setContextPath(detection == null ? null : detection.getContextPath());
        runtime.setInternalBaseUrl(null);
        runtime.setPublicBaseUrl(null);
        runtime.setLastHealthStatus(null);
        runtime.setLastError(lastError);
        return runtime;
    }

    private SourceRuntimeResponse notCreatedResponse(UUID projectId) {
        return SourceRuntimeResponse.builder()
                .projectId(projectId)
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(RuntimeStatus.NOT_CREATED)
                .runtimeType(RuntimeType.UNKNOWN)
                .build();
    }

    private SourceRuntimeResponse toResponse(SourceRuntime runtime) {
        return SourceRuntimeResponse.builder()
                .id(runtime.getId())
                .projectId(runtime.getSourceProject() == null ? null : runtime.getSourceProject().getId())
                .sourceVersionId(runtime.getSourceVersion() == null ? null : runtime.getSourceVersion().getId())
                .runtimeMode(runtime.getRuntimeMode())
                .runtimeStatus(runtime.getRuntimeStatus())
                .runtimeType(runtime.getRuntimeType())
                .publicBaseUrl(runtime.getPublicBaseUrl())
                .detectedPort(runtime.getDetectedPort())
                .contextPath(runtime.getContextPath())
                .healthCheckPath(runtime.getHealthCheckPath())
                .lastHealthStatus(runtime.getLastHealthStatus())
                .lastError(runtime.getLastError())
                .buildStartedAt(runtime.getBuildStartedAt())
                .buildFinishedAt(runtime.getBuildFinishedAt())
                .startedAt(runtime.getStartedAt())
                .stoppedAt(runtime.getStoppedAt())
                .createdAt(runtime.getCreatedAt())
                .updatedAt(runtime.getUpdatedAt())
                .build();
    }
}
