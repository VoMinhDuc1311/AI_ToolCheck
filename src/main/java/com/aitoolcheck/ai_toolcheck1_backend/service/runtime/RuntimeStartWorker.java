package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeStartWorker {

    private final SourceRuntimeLifecycleService lifecycleService;

    @Async("runtimeStartExecutor")
    public void runStartAsync(UUID runtimeId, SourceProject project, RuntimeDetectionResult detection, BuildStrategy strategy, DockerRuntimeOrchestrator orchestrator) {
        try {
            orchestrator.runStartSynchronously(runtimeId, project, detection, strategy);
        } catch (Throwable t) {
            log.error("[RuntimeStartWorker] Async runtime start failed outside orchestrator projectId={} runtimeId={} strategy={}: {}",
                    project == null ? null : project.getId(), runtimeId, strategy, t.getMessage(), t);
            try {
                lifecycleService.markBuildFailed(runtimeId, "Async runtime worker failed: " + t.getMessage());
            } catch (Exception dbEx) {
                log.error("[RuntimeStartWorker] Could not persist async worker failure runtimeId={}: {}",
                        runtimeId, dbEx.getMessage(), dbEx);
            }
        }
    }
}
