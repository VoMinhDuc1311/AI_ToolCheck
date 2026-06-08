package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeLifecycleService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RuntimeStartWorkerTest {

    @Test
    void workerUnexpectedException_marksTerminalFailure() {
        SourceRuntimeLifecycleService lifecycleService = mock(SourceRuntimeLifecycleService.class);
        RuntimeStartWorker worker = new RuntimeStartWorker(lifecycleService);
        DockerRuntimeOrchestrator orchestrator = mock(DockerRuntimeOrchestrator.class);
        UUID runtimeId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(UUID.randomUUID());
        RuntimeDetectionResult detection = RuntimeDetectionResult.builder().supported(true).build();
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(orchestrator).runStartSynchronously(runtimeId, project, detection, BuildStrategy.AUTO);

        worker.runStartAsync(runtimeId, project, detection, BuildStrategy.AUTO, orchestrator);

        verify(lifecycleService).markBuildFailed(eq(runtimeId), contains("Async runtime worker failed: boom"));
    }
}
