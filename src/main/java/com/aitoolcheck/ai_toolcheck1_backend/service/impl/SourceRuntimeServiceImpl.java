package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
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

    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final ProjectAccessService projectAccessService;
    private final RuntimeAutoProperties runtimeAutoProperties;

    @Override
    @Transactional(readOnly = true)
    public SourceRuntimeResponse getCurrentRuntime(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);
        return sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .map(this::toResponse)
                .orElseGet(() -> notCreatedResponse(projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public SourceRuntimeResponse ensureRuntimeReady(UUID projectId) {
        projectAccessService.requireCanCreateTestRun(projectId);
        if (!runtimeAutoProperties.isEnabled()) {
            throw new BadRequestException("Auto runtime from uploaded source is not enabled yet. Use External Base URL.");
        }
        throw new BadRequestException(AUTO_RUNTIME_NOT_IMPLEMENTED_MESSAGE);
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeActionResponse startRuntime(UUID projectId) {
        projectAccessService.requireCanManageProject(projectId);
        return disabledOrNotImplemented(projectId);
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeActionResponse rebuildRuntime(UUID projectId) {
        projectAccessService.requireCanManageProject(projectId);
        return disabledOrNotImplemented(projectId);
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

    private RuntimeActionResponse disabledOrNotImplemented(UUID projectId) {
        SourceRuntimeResponse runtime = sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(projectId)
                .map(this::toResponse)
                .orElseGet(() -> notCreatedResponse(projectId));
        if (!runtimeAutoProperties.isEnabled()) {
            return RuntimeActionResponse.builder()
                    .code(AUTO_RUNTIME_DISABLED_CODE)
                    .message(AUTO_RUNTIME_DISABLED_MESSAGE)
                    .runtime(runtime)
                    .build();
        }
        return RuntimeActionResponse.builder()
                .code(AUTO_RUNTIME_NOT_IMPLEMENTED_CODE)
                .message(AUTO_RUNTIME_NOT_IMPLEMENTED_MESSAGE)
                .runtime(runtime)
                .build();
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
