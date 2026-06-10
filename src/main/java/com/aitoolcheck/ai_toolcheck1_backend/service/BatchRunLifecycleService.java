package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStep;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRun;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRunItem;

import java.util.List;
import java.util.UUID;

public interface BatchRunLifecycleService {
    BatchRun markBatchRunning(UUID batchId);
    BatchRun finalizeBatch(UUID batchId);
    BatchRun markBatchFailed(UUID batchId, String error);
    BatchRun findBatchFresh(UUID batchId);
    BatchRunItem findItemFresh(UUID itemId);
    List<BatchRunItem> findItemsFresh(UUID batchId);
    BatchRunItem markItemRunning(UUID itemId, BatchRunStep step);
    BatchRunItem markItemStep(UUID itemId, BatchRunStep step);
    BatchRunItem attachApiVersion(UUID itemId, UUID apiDocumentVersionId);
    BatchRunItem attachRuntime(UUID itemId, UUID runtimeId);
    BatchRunItem attachTestRun(UUID itemId, UUID testRunId);
    BatchRunItem markItemSuccess(UUID itemId);
    BatchRunItem markItemFailed(UUID itemId, String error);
    BatchRunItem appendItemWarning(UUID itemId, String warning);
    void cancelPendingItems(UUID batchId);
}
