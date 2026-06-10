package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunItemStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStep;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRun;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRun;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.BatchRunLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BatchRunLifecycleServiceImpl implements BatchRunLifecycleService {

    private final BatchRunRepository batchRunRepository;
    private final BatchRunItemRepository batchRunItemRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final TestRunRepository testRunRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRun markBatchRunning(UUID batchId) {
        BatchRun batchRun = getBatch(batchId);
        batchRun.setStatus(BatchRunStatus.RUNNING);
        batchRun.setStartedAt(LocalDateTime.now());
        batchRun.setCompletedAt(null);
        batchRun.setErrorMessage(null);
        updateAggregate(batchRun);
        return batchRunRepository.save(batchRun);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRun finalizeBatch(UUID batchId) {
        BatchRun batchRun = getBatch(batchId);
        updateAggregate(batchRun);
        if (batchRun.getStatus() == BatchRunStatus.CANCELLED) {
            return batchRunRepository.save(batchRun);
        }
        if (batchRun.getSuccessCount() > 0 && batchRun.getFailedCount() == 0) {
            batchRun.setStatus(BatchRunStatus.COMPLETED);
        } else if (batchRun.getSuccessCount() > 0) {
            batchRun.setStatus(BatchRunStatus.PARTIAL);
        } else {
            batchRun.setStatus(BatchRunStatus.FAILED);
        }
        batchRun.setCompletedAt(LocalDateTime.now());
        return batchRunRepository.save(batchRun);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRun markBatchFailed(UUID batchId, String error) {
        BatchRun batchRun = getBatch(batchId);
        updateAggregate(batchRun);
        batchRun.setStatus(BatchRunStatus.FAILED);
        batchRun.setErrorMessage(error);
        batchRun.setCompletedAt(LocalDateTime.now());
        return batchRunRepository.save(batchRun);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public BatchRun findBatchFresh(UUID batchId) {
        return getBatch(batchId);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem findItemFresh(UUID itemId) {
        return getItem(itemId);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<BatchRunItem> findItemsFresh(UUID batchId) {
        return batchRunItemRepository.findByBatchRun_IdOrderByCreatedAtAsc(batchId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem markItemRunning(UUID itemId, BatchRunStep step) {
        BatchRunItem item = getItem(itemId);
        item.setStatus(BatchRunItemStatus.RUNNING);
        item.setCurrentStep(step);
        item.setStartedAt(LocalDateTime.now());
        item.setCompletedAt(null);
        item.setErrorMessage(null);
        return batchRunItemRepository.save(item);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem markItemStep(UUID itemId, BatchRunStep step) {
        BatchRunItem item = getItem(itemId);
        item.setCurrentStep(step);
        return batchRunItemRepository.save(item);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem attachApiVersion(UUID itemId, UUID apiDocumentVersionId) {
        BatchRunItem item = getItem(itemId);
        if (apiDocumentVersionId != null) {
            item.setApiDocumentVersion(apiDocumentVersionRepository.findById(apiDocumentVersionId)
                    .orElseThrow(() -> new ResourceNotFoundException("ApiDocumentVersion not found: " + apiDocumentVersionId)));
        }
        return batchRunItemRepository.save(item);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem attachRuntime(UUID itemId, UUID runtimeId) {
        BatchRunItem item = getItem(itemId);
        if (runtimeId != null) {
            item.setRuntime(sourceRuntimeRepository.findById(runtimeId)
                    .orElseThrow(() -> new ResourceNotFoundException("SourceRuntime not found: " + runtimeId)));
        }
        return batchRunItemRepository.save(item);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem attachTestRun(UUID itemId, UUID testRunId) {
        BatchRunItem item = getItem(itemId);
        TestRun testRun = testRunRepository.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found: " + testRunId));
        item.setTestRun(testRun);
        return batchRunItemRepository.save(item);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem markItemSuccess(UUID itemId) {
        BatchRunItem item = getItem(itemId);
        item.setCurrentStep(BatchRunStep.DONE);
        item.setStatus(BatchRunItemStatus.SUCCESS);
        item.setCompletedAt(LocalDateTime.now());
        return batchRunItemRepository.save(item);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem markItemFailed(UUID itemId, String error) {
        BatchRunItem item = getItem(itemId);
        item.setStatus(BatchRunItemStatus.FAILED);
        item.setErrorMessage(error);
        item.setCompletedAt(LocalDateTime.now());
        return batchRunItemRepository.save(item);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRunItem appendItemWarning(UUID itemId, String warning) {
        BatchRunItem item = getItem(itemId);
        String existing = item.getErrorMessage();
        item.setErrorMessage(existing == null || existing.isBlank() ? warning : existing + " | " + warning);
        return batchRunItemRepository.save(item);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelPendingItems(UUID batchId) {
        List<BatchRunItem> pending = batchRunItemRepository.findByBatchRun_IdAndStatusInOrderByCreatedAtAsc(
                batchId, List.of(BatchRunItemStatus.PENDING));
        for (BatchRunItem item : pending) {
            item.setStatus(BatchRunItemStatus.CANCELLED);
            item.setCompletedAt(LocalDateTime.now());
            batchRunItemRepository.save(item);
        }
    }

    private void updateAggregate(BatchRun batchRun) {
        UUID id = batchRun.getId();
        batchRun.setSuccessCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.SUCCESS));
        batchRun.setFailedCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.FAILED));
        batchRun.setSkippedCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.SKIPPED));
        batchRun.setRunningCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.RUNNING));
    }

    private BatchRun getBatch(UUID batchId) {
        return batchRunRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("BatchRun not found: " + batchId));
    }

    private BatchRunItem getItem(UUID itemId) {
        return batchRunItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("BatchRunItem not found: " + itemId));
    }
}
