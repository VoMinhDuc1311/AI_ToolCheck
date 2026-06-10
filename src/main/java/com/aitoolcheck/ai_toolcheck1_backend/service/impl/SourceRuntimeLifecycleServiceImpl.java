package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DockerfileSource;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeStatusSnapshot;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SourceRuntimeLifecycleServiceImpl implements SourceRuntimeLifecycleService {

    private final SourceRuntimeRepository sourceRuntimeRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceRuntime createBuildingRuntime(SourceProject project, UUID sourceVersionId, BuildStrategy strategy) {
        String inheritedHealthCheckPath = sourceRuntimeRepository.findFirstBySourceProject_IdOrderByUpdatedAtDesc(project.getId())
                .map(SourceRuntime::getHealthCheckPath)
                .orElse(null);

        // Stop any old runtimes for this project first
        sourceRuntimeRepository.findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(project.getId(), RuntimeStatus.UP)
                .ifPresent(existing -> {
                    existing.setRuntimeStatus(RuntimeStatus.STOPPED);
                    existing.setStoppedAt(LocalDateTime.now());
                    sourceRuntimeRepository.save(existing);
                    log.info("[LifecycleService] Stopped existing UP runtime id={} before building new one", existing.getId());
                });

        SourceRuntime runtime = SourceRuntime.builder()
                .sourceProject(project)
                .sourceVersion(sourceVersionId != null ? SourceUploadVersion.builder().id(sourceVersionId).build() : null)
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(RuntimeStatus.BUILDING)
                .runtimeType(RuntimeType.UNKNOWN)
                .buildStrategyRequested(strategy)
                .healthCheckPath(inheritedHealthCheckPath)
                .buildStartedAt(LocalDateTime.now())
                .build();
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceRuntime markBuildFailed(UUID runtimeId, String error) {
        SourceRuntime runtime = getRuntime(runtimeId);
        if (isTerminalStop(runtime.getRuntimeStatus())) {
            return runtime;
        }
        runtime.setRuntimeStatus(RuntimeStatus.BUILD_FAILED);
        runtime.setLastError(error);
        runtime.setBuildFinishedAt(LocalDateTime.now());
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceRuntime markStarting(UUID runtimeId, String containerName, String imageTag, DockerfileSource dockerfileSource, BuildStrategy buildStrategyUsed, String fallbackReason, LocalDateTime buildFinishedAt) {
        SourceRuntime runtime = getRuntime(runtimeId);
        if (isTerminalStop(runtime.getRuntimeStatus())) {
            return runtime;
        }
        runtime.setRuntimeStatus(RuntimeStatus.STARTING);
        runtime.setContainerName(containerName);
        runtime.setImageName(imageTag);
        runtime.setDockerfileSource(dockerfileSource);
        runtime.setBuildStrategyUsed(buildStrategyUsed);
        runtime.setFallbackReason(fallbackReason);
        runtime.setBuildFinishedAt(buildFinishedAt != null ? buildFinishedAt : LocalDateTime.now());
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceRuntime markStartFailed(UUID runtimeId, String error) {
        SourceRuntime runtime = getRuntime(runtimeId);
        if (isTerminalStop(runtime.getRuntimeStatus())) {
            return runtime;
        }
        runtime.setRuntimeStatus(RuntimeStatus.START_FAILED);
        runtime.setLastError(error);
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceRuntime markUnhealthy(UUID runtimeId, String lastHealthStatus, String error) {
        SourceRuntime runtime = getRuntime(runtimeId);
        if (isTerminalStop(runtime.getRuntimeStatus())) {
            return runtime;
        }
        runtime.setRuntimeStatus(RuntimeStatus.UNHEALTHY);
        runtime.setLastHealthStatus(lastHealthStatus);
        runtime.setLastError(error);
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceRuntime markUp(UUID runtimeId, String publicBaseUrl, int detectedPort, String containerName, String lastHealthStatus, LocalDateTime startedAt) {
        SourceRuntime runtime = getRuntime(runtimeId);
        if (isTerminalStop(runtime.getRuntimeStatus())) {
            return runtime;
        }
        runtime.setRuntimeStatus(RuntimeStatus.UP);
        runtime.setPublicBaseUrl(publicBaseUrl);
        runtime.setDetectedPort(detectedPort);
        runtime.setContainerName(containerName);
        runtime.setLastHealthStatus(lastHealthStatus);
        runtime.setStartedAt(startedAt != null ? startedAt : LocalDateTime.now());
        runtime.setLastError(null);
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceRuntime markStopping(UUID runtimeId) {
        SourceRuntime runtime = getRuntime(runtimeId);
        runtime.setRuntimeStatus(RuntimeStatus.STOPPING);
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceRuntime markStopped(UUID runtimeId) {
        SourceRuntime runtime = getRuntime(runtimeId);
        runtime.setRuntimeStatus(RuntimeStatus.STOPPED);
        runtime.setStoppedAt(LocalDateTime.now());
        return sourceRuntimeRepository.save(runtime);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<SourceRuntime> findFresh(UUID runtimeId) {
        return sourceRuntimeRepository.findById(runtimeId);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RuntimeStatusSnapshot findStatusSnapshot(UUID runtimeId) {
        SourceRuntime runtime = getRuntime(runtimeId);
        return RuntimeStatusSnapshot.builder()
                .runtimeId(runtime.getId())
                .status(runtime.getRuntimeStatus())
                .publicBaseUrl(runtime.getPublicBaseUrl())
                .lastHealthStatus(runtime.getLastHealthStatus())
                .lastError(runtime.getLastError())
                .updatedAt(runtime.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RuntimeStatus getFreshStatus(UUID runtimeId) {
        return getRuntime(runtimeId).getRuntimeStatus();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isStartCancelled(UUID runtimeId) {
        RuntimeStatus status = getRuntime(runtimeId).getRuntimeStatus();
        return isTerminalStop(status);
    }

    private SourceRuntime getRuntime(UUID runtimeId) {
        return sourceRuntimeRepository.findById(runtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("SourceRuntime not found: " + runtimeId));
    }

    private boolean isTerminalStop(RuntimeStatus status) {
        return status == RuntimeStatus.STOPPING || status == RuntimeStatus.STOPPED;
    }
}
