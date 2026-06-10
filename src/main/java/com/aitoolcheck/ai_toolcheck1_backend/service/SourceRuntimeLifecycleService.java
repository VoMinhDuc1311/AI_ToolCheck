package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DockerfileSource;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeStatusSnapshot;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SourceRuntimeLifecycleService {
    SourceRuntime createBuildingRuntime(SourceProject project, UUID sourceVersionId, BuildStrategy strategy);
    SourceRuntime markBuildFailed(UUID runtimeId, String error);
    SourceRuntime markStarting(UUID runtimeId, String containerName, String imageTag, DockerfileSource dockerfileSource, BuildStrategy buildStrategyUsed, String fallbackReason, LocalDateTime buildFinishedAt);
    SourceRuntime markStartFailed(UUID runtimeId, String error);
    SourceRuntime markUnhealthy(UUID runtimeId, String lastHealthStatus, String error);
    SourceRuntime markUp(UUID runtimeId, String publicBaseUrl, int detectedPort, String containerName, String lastHealthStatus, LocalDateTime startedAt);
    SourceRuntime markStopping(UUID runtimeId);
    SourceRuntime markStopped(UUID runtimeId);
    Optional<SourceRuntime> findFresh(UUID runtimeId);
    RuntimeStatusSnapshot findStatusSnapshot(UUID runtimeId);
    RuntimeStatus getFreshStatus(UUID runtimeId);
    boolean isStartCancelled(UUID runtimeId);
}
