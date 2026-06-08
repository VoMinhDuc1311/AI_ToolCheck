package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.BatchRunOptionsRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.CreateBatchRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunReportResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunItemStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStep;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRun;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.BatchRunItemRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.BatchRunRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.BatchRunLifecycleService;
import com.aitoolcheck.ai_toolcheck1_backend.service.BatchRunService;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
import com.aitoolcheck.ai_toolcheck1_backend.service.worker.BatchRunWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchRunServiceImpl implements BatchRunService {

    private final BatchRunRepository batchRunRepository;
    private final BatchRunItemRepository batchRunItemRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final SourceRuntimeService sourceRuntimeService;
    private final BatchRunLifecycleService batchRunLifecycleService;
    private final BatchRunWorker batchRunWorker;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public BatchRunResponse create(CreateBatchRunRequest request) {
        if (request == null) {
            throw new BadRequestException("CreateBatchRunRequest is required");
        }
        if (request.getProjectIds() == null || request.getProjectIds().isEmpty()) {
            throw new BadRequestException("projectIds must not be empty");
        }

        BatchRunOptionsRequest options = request.getOptions() == null
                ? new BatchRunOptionsRequest()
                : request.getOptions();

        AppUser actor = currentUserService.getCurrentUser();

        BatchRun batchRun = BatchRun.builder()
                .name(request.getName().trim())
                .status(BatchRunStatus.PENDING)
                .totalItems(request.getProjectIds().size())
                .successCount(0)
                .failedCount(0)
                .skippedCount(0)
                .runningCount(0)
                .generateOpenApi(options.shouldGenerateOpenApi())
                .generateTestCases(options.shouldGenerateTestCases())
                .startRuntime(options.shouldStartRuntime())
                .executeTestRun(options.shouldExecuteTestRun())
                .stopRuntimeAfterRun(options.shouldStopRuntimeAfterRun())
                .maxConcurrency(options.safeMaxConcurrency())
                .maxRetries(options.safeMaxRetries())
                .executionMode(options.safeExecutionMode())
                .buildStrategy(options.safeBuildStrategy())
                .externalBaseUrl(blankToNull(options.getExternalBaseUrl()))
                .createdBy(actor.getId())
                .build();
        BatchRun saved = batchRunRepository.save(batchRun);

        for (UUID projectId : request.getProjectIds()) {
            SourceProject project = sourceProjectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("SourceProject not found: " + projectId));
            batchRunItemRepository.save(BatchRunItem.builder()
                    .batchRun(saved)
                    .sourceProject(project)
                    .currentStep(BatchRunStep.GENERATE_OPENAPI)
                    .status(BatchRunItemStatus.PENDING)
                    .retryCount(0)
                    .maxRetries(options.safeMaxRetries())
                    .build());
        }

        log.info("[BatchRun] created batchId={} totalItems={}", saved.getId(), saved.getTotalItems());
        return toResponse(saved);
    }

    @Override
    public BatchRunResponse start(UUID id) {
        BatchRun batchRun = findBatchRun(id);
        if (batchRun.getStatus() == BatchRunStatus.RUNNING) {
            throw new BadRequestException("BatchRun is already RUNNING: " + id);
        }
        if (batchRun.getStatus() != BatchRunStatus.PENDING) {
            throw new BadRequestException("BatchRun can only be started from PENDING status: " + id);
        }
        if (batchRun.getCreatedBy() == null) {
            AppUser actor = currentUserService.getCurrentUser();
            batchRun.setCreatedBy(actor.getId());
            batchRunRepository.save(batchRun);
            log.info("[BatchRun] start actor backfilled batchId={} userId={} email={}",
                    id, actor.getId(), actor.getEmail());
        }

        BatchRun running = batchRunLifecycleService.markBatchRunning(id);
        batchRunWorker.runAsync(id);
        log.info("[BatchRun] start accepted batchId={} dispatched=true", id);
        return toResponse(running);
    }

    @Override
    @Transactional
    public BatchRunResponse cancel(UUID id) {
        BatchRun batchRun = findBatchRun(id);
        if (batchRun.getStatus() == BatchRunStatus.CANCELLED) {
            return toResponse(batchRun);
        }

        List<BatchRunItem> runningItems = batchRunItemRepository.findByBatchRun_IdAndStatusInOrderByCreatedAtAsc(
                id, List.of(BatchRunItemStatus.RUNNING));
        for (BatchRunItem item : runningItems) {
            try {
                sourceRuntimeService.stopRuntime(item.getSourceProject().getId());
            } catch (Exception e) {
                item.setErrorMessage(appendMessage(item.getErrorMessage(), "Cancel stopRuntime failed: " + e.getMessage()));
            }
            item.setStatus(BatchRunItemStatus.CANCELLED);
            item.setCompletedAt(LocalDateTime.now());
            batchRunItemRepository.save(item);
        }
        batchRunLifecycleService.cancelPendingItems(id);

        batchRun.setStatus(BatchRunStatus.CANCELLED);
        batchRun.setCompletedAt(LocalDateTime.now());
        refreshAggregate(batchRun);
        return toResponse(batchRunRepository.save(batchRun));
    }

    @Override
    @Transactional(readOnly = true)
    public BatchRunResponse getById(UUID id) {
        return toResponse(findBatchRun(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BatchRunItemResponse> getItems(UUID id) {
        findBatchRun(id);
        return batchRunItemRepository.findByBatchRun_IdOrderByCreatedAtAsc(id).stream()
                .map(this::toItemResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BatchRunReportResponse getReport(UUID id) {
        BatchRun batchRun = findBatchRun(id);
        List<BatchRunItemResponse> items = batchRunItemRepository.findByBatchRun_IdOrderByCreatedAtAsc(id).stream()
                .map(this::toItemResponse)
                .toList();
        return BatchRunReportResponse.builder()
                .batchRunId(batchRun.getId())
                .name(batchRun.getName())
                .status(batchRun.getStatus())
                .totalItems(batchRun.getTotalItems())
                .successCount(batchRun.getSuccessCount())
                .failedCount(batchRun.getFailedCount())
                .skippedCount(batchRun.getSkippedCount())
                .runningCount(batchRun.getRunningCount())
                .startedAt(batchRun.getStartedAt())
                .completedAt(batchRun.getCompletedAt())
                .items(items)
                .build();
    }

    private void refreshAggregate(BatchRun batchRun) {
        UUID id = batchRun.getId();
        batchRun.setSuccessCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.SUCCESS));
        batchRun.setFailedCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.FAILED));
        batchRun.setSkippedCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.SKIPPED));
        batchRun.setRunningCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.RUNNING));
    }

    private BatchRun findBatchRun(UUID id) {
        return batchRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BatchRun not found: " + id));
    }

    private BatchRunResponse toResponse(BatchRun batchRun) {
        return BatchRunResponse.builder()
                .id(batchRun.getId())
                .name(batchRun.getName())
                .status(batchRun.getStatus())
                .totalItems(batchRun.getTotalItems())
                .successCount(batchRun.getSuccessCount())
                .failedCount(batchRun.getFailedCount())
                .skippedCount(batchRun.getSkippedCount())
                .runningCount(batchRun.getRunningCount())
                .startedAt(batchRun.getStartedAt())
                .completedAt(batchRun.getCompletedAt())
                .createdBy(batchRun.getCreatedBy())
                .errorMessage(batchRun.getErrorMessage())
                .buildStrategy(batchRun.getBuildStrategy())
                .createdAt(batchRun.getCreatedAt())
                .updatedAt(batchRun.getUpdatedAt())
                .build();
    }

    private BatchRunItemResponse toItemResponse(BatchRunItem item) {
        return BatchRunItemResponse.builder()
                .id(item.getId())
                .batchRunId(item.getBatchRun().getId())
                .projectId(item.getSourceProject().getId())
                .currentStep(item.getCurrentStep())
                .status(item.getStatus())
                .retryCount(item.getRetryCount())
                .maxRetries(item.getMaxRetries())
                .errorMessage(item.getErrorMessage())
                .apiDocumentVersionId(item.getApiDocumentVersion() == null ? null : item.getApiDocumentVersion().getId())
                .testRunId(item.getTestRun() == null ? null : item.getTestRun().getId())
                .runtimeId(item.getRuntime() == null ? null : item.getRuntime().getId())
                .startedAt(item.getStartedAt())
                .completedAt(item.getCompletedAt())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }

    private String appendMessage(String current, String addition) {
        return hasText(current) ? current + " | " + addition : addition;
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
