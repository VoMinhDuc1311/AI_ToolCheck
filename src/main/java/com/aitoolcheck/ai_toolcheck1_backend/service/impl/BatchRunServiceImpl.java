package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.BatchRunOptionsRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.CreateBatchRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunReportResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res.OpenApiGenerateResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.*;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchRunServiceImpl implements BatchRunService {

    private static final Duration AI_JOB_TIMEOUT = Duration.ofMinutes(2);

    private final BatchRunRepository batchRunRepository;
    private final BatchRunItemRepository batchRunItemRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final AiJobLogRepository aiJobLogRepository;
    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final TestRunRepository testRunRepository;
    private final OpenApiGeneratorService openApiGeneratorService;
    private final TestCaseService testCaseService;
    private final SourceRuntimeService sourceRuntimeService;
    private final TestRunService testRunService;

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

        markBatchRunning(id);
        List<BatchRunItem> items = batchRunItemRepository.findByBatchRun_IdOrderByCreatedAtAsc(id);
        for (BatchRunItem item : items) {
            BatchRun current = findBatchRun(id);
            if (current.getStatus() == BatchRunStatus.CANCELLED) {
                cancelPendingItems(id);
                break;
            }
            processItem(item.getId());
        }
        BatchRun completed = finalizeBatch(id);
        log.info("[BatchRun] finished batchId={} status={} success={} failed={}",
                completed.getId(), completed.getStatus(), completed.getSuccessCount(), completed.getFailedCount());
        return toResponse(completed);
    }

    @Override
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
        cancelPendingItems(id);

        batchRun.setStatus(BatchRunStatus.CANCELLED);
        batchRun.setCompletedAt(LocalDateTime.now());
        updateAggregate(batchRun);
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

    private void processItem(UUID itemId) {
        BatchRunItem item = markItemRunning(itemId);
        try {
            UUID batchId = item.getBatchRun().getId();
            UUID projectId = item.getSourceProject().getId();
            SourceProject project = sourceProjectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("SourceProject not found: " + projectId));
            log.info("[BatchRun] item start batchId={} itemId={} projectId={}",
                    batchId, item.getId(), projectId);

            ApiDocumentVersion version = runOpenApiStep(item, projectId);
            runTestCaseStep(item, projectId);
            SourceRuntimeResponse runtime = runRuntimeStep(item, project);
            TestRunDetailResponse testRun = runCreateTestRunStep(item, project, runtime);
            runExecuteTestRunStep(item, testRun);
            runStopRuntimeStep(item, projectId);
            markItemSuccess(item.getId());
        } catch (Exception e) {
            markItemFailed(item.getId(), e.getMessage());
            log.warn("[BatchRun] item failed itemId={} reason={}", itemId, e.getMessage());
        }
    }

    private ApiDocumentVersion runOpenApiStep(BatchRunItem item, UUID projectId) {
        updateStep(item.getId(), BatchRunStep.GENERATE_OPENAPI);
        BatchRun batchRun = findBatchRun(item.getBatchRun().getId());
        ApiDocumentVersion existing = latestOpenApi(projectId);
        if (!batchRun.getGenerateOpenApi()) {
            attachApiVersion(item.getId(), existing);
            return existing;
        }
        if (existing != null && hasText(existing.getContentJson())) {
            attachApiVersion(item.getId(), existing);
            return existing;
        }
        OpenApiGenerateResponse response = openApiGeneratorService.generateAndSaveOpenApi(projectId);
        ApiDocumentVersion generated = apiDocumentVersionRepository.findById(response.getApiDocumentVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Generated ApiDocumentVersion not found: " + response.getApiDocumentVersionId()));
        attachApiVersion(item.getId(), generated);
        return generated;
    }

    private void runTestCaseStep(BatchRunItem item, UUID projectId) {
        updateStep(item.getId(), BatchRunStep.GENERATE_TEST_CASES);
        BatchRun batchRun = findBatchRun(item.getBatchRun().getId());
        if (!batchRun.getGenerateTestCases()) {
            return;
        }
        long activeAiCases = testCaseRepository.countBySourceProject_IdAndGeneratedByAndActiveFlagTrueAndDeletedFlagFalse(
                projectId, GeneratedBy.AI);
        if (activeAiCases > 0) {
            return;
        }
        if (apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId).isEmpty()) {
            throw new BadRequestException("No active API endpoints available for AI testcase generation.");
        }
        testCaseService.generateTestCaseAsync(com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.GenerateTestCaseRequest.builder()
                .projectId(projectId)
                .overwriteExisting(false)
                .build());
        waitForAiJobs(projectId);
        long generated = testCaseRepository.countBySourceProject_IdAndGeneratedByAndActiveFlagTrueAndDeletedFlagFalse(
                projectId, GeneratedBy.AI);
        if (generated == 0) {
            throw new BadRequestException("AI testcase generation completed without active generated testcases.");
        }
    }

    private SourceRuntimeResponse runRuntimeStep(BatchRunItem item, SourceProject project) {
        updateStep(item.getId(), BatchRunStep.START_RUNTIME);
        BatchRun batchRun = findBatchRun(item.getBatchRun().getId());
        SourceRuntimeResponse runtime;
        if (batchRun.getStartRuntime()) {
            RuntimeActionResponse response = sourceRuntimeService.startRuntime(project.getId(), batchRun.getBuildStrategy());
            runtime = response.getRuntime();
            if (runtime == null) {
                throw new BadRequestException("Runtime start failed: startRuntime returned no runtime.");
            }
            if (runtime.getRuntimeStatus() == RuntimeStatus.BUILDING || runtime.getRuntimeStatus() == RuntimeStatus.STARTING) {
                RuntimeActionResponse waited = sourceRuntimeService.waitForRuntimeTerminalState(project.getId(), runtime.getId(), 300);
                runtime = waited.getRuntime();
            }
            if (runtime == null || runtime.getRuntimeStatus() != RuntimeStatus.UP) {
                String error = runtime != null && runtime.getLastError() != null ? runtime.getLastError() : "Runtime failed to reach UP status.";
                throw new BadRequestException("Runtime start failed: " + error);
            }
        } else {
            String baseUrl = sourceRuntimeService.resolveBaseUrlForTestRun(
                    project.getId(), batchRun.getExternalBaseUrl(), project.getDefaultTargetBaseUrl());
            runtime = sourceRuntimeRepository
                    .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(project.getId(), RuntimeStatus.UP)
                    .map(this::toRuntimeResponse)
                    .orElse(SourceRuntimeResponse.builder()
                            .projectId(project.getId())
                            .runtimeStatus(RuntimeStatus.UP)
                            .publicBaseUrl(baseUrl)
                            .build());
        }
        attachRuntime(item.getId(), runtime.getId());
        return runtime;
    }

    private TestRunDetailResponse runCreateTestRunStep(BatchRunItem item, SourceProject project, SourceRuntimeResponse runtime) {
        updateStep(item.getId(), BatchRunStep.CREATE_TEST_RUN);
        BatchRun batchRun = findBatchRun(item.getBatchRun().getId());
        List<TestCase> testCases = testCaseRepository
                .findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(project.getId());
        if (testCases.isEmpty()) {
            throw new BadRequestException("No active testcases found for project: " + project.getId());
        }
        String baseUrl = batchRun.getStartRuntime()
                ? null
                : batchRun.getExternalBaseUrl();
        TestRunDetailResponse response = testRunService.create(CreateTestRunRequest.builder()
                .projectId(project.getId())
                .runName(batchRun.getName() + " - " + project.getProjectName())
                .description("BatchRun " + batchRun.getId() + " item " + item.getId())
                .baseUrl(baseUrl)
                .executionMode(batchRun.getExecutionMode())
                .runtimeMode(runtime == null ? null : runtime.getRuntimeMode())
                .includeAllActive(true)
                .build());
        attachTestRun(item.getId(), response.getId());
        return response;
    }

    private void runExecuteTestRunStep(BatchRunItem item, TestRunDetailResponse testRun) {
        updateStep(item.getId(), BatchRunStep.EXECUTE_TEST_RUN);
        BatchRun batchRun = findBatchRun(item.getBatchRun().getId());
        if (!batchRun.getExecuteTestRun()) {
            return;
        }
        TestRunDetailResponse executed = testRunService.execute(testRun.getId());
        if (executed.getRunStatus() == RunStatus.FAILED) {
            throw new BadRequestException("TestRun execution failed: " + executed.getId());
        }
    }

    private void runStopRuntimeStep(BatchRunItem item, UUID projectId) {
        updateStep(item.getId(), BatchRunStep.STOP_RUNTIME);
        BatchRun batchRun = findBatchRun(item.getBatchRun().getId());
        if (!batchRun.getStopRuntimeAfterRun()) {
            return;
        }
        try {
            sourceRuntimeService.stopRuntime(projectId);
        } catch (Exception e) {
            appendItemWarning(item.getId(), "stopRuntime failed: " + e.getMessage());
        }
    }

    private void waitForAiJobs(UUID projectId) {
        LocalDateTime deadline = LocalDateTime.now().plus(AI_JOB_TIMEOUT);
        while (LocalDateTime.now().isBefore(deadline)) {
            List<AiJobLog> pending = aiJobLogRepository
                    .findBySourceProject_IdAndJobTypeAndExecutionStatusInOrderByStartedAtDesc(
                            projectId,
                            JobType.TEST_CASE_GENERATION,
                            List.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING));
            if (pending.isEmpty()) {
                List<AiJobLog> failed = aiJobLogRepository
                        .findBySourceProject_IdAndJobTypeAndExecutionStatusInOrderByStartedAtDesc(
                                projectId,
                                JobType.TEST_CASE_GENERATION,
                                List.of(ExecutionStatus.FAILED));
                if (!failed.isEmpty()) {
                    throw new BadRequestException("AI testcase generation failed: " + failed.get(0).getErrorMessage());
                }
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BadRequestException("Interrupted while waiting for AI testcase generation.");
            }
        }
        throw new BadRequestException("Timed out waiting for AI testcase generation.");
    }

    @Transactional
    protected BatchRunItem markItemRunning(UUID itemId) {
        BatchRunItem item = findItem(itemId);
        item.setStatus(BatchRunItemStatus.RUNNING);
        item.setStartedAt(LocalDateTime.now());
        item.setErrorMessage(null);
        return batchRunItemRepository.save(item);
    }

    @Transactional
    protected void updateStep(UUID itemId, BatchRunStep step) {
        BatchRunItem item = findItem(itemId);
        item.setCurrentStep(step);
        batchRunItemRepository.save(item);
        log.info("[BatchRun] step batchId={} itemId={} projectId={} step={}",
                item.getBatchRun().getId(), itemId, item.getSourceProject().getId(), step);
    }

    @Transactional
    protected void markItemSuccess(UUID itemId) {
        BatchRunItem item = findItem(itemId);
        item.setCurrentStep(BatchRunStep.DONE);
        item.setStatus(BatchRunItemStatus.SUCCESS);
        item.setCompletedAt(LocalDateTime.now());
        batchRunItemRepository.save(item);
    }

    @Transactional
    protected void markItemFailed(UUID itemId, String message) {
        BatchRunItem item = findItem(itemId);
        item.setStatus(BatchRunItemStatus.FAILED);
        item.setErrorMessage(message);
        item.setCompletedAt(LocalDateTime.now());
        batchRunItemRepository.save(item);
    }

    @Transactional
    protected void markBatchRunning(UUID id) {
        BatchRun batchRun = findBatchRun(id);
        batchRun.setStatus(BatchRunStatus.RUNNING);
        batchRun.setStartedAt(LocalDateTime.now());
        batchRunRepository.save(batchRun);
    }

    @Transactional
    protected BatchRun finalizeBatch(UUID id) {
        BatchRun batchRun = findBatchRun(id);
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

    private void updateAggregate(BatchRun batchRun) {
        UUID id = batchRun.getId();
        batchRun.setSuccessCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.SUCCESS));
        batchRun.setFailedCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.FAILED));
        batchRun.setSkippedCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.SKIPPED));
        batchRun.setRunningCount((int) batchRunItemRepository.countByBatchRun_IdAndStatus(id, BatchRunItemStatus.RUNNING));
    }

    private void cancelPendingItems(UUID batchRunId) {
        List<BatchRunItem> pending = batchRunItemRepository.findByBatchRun_IdAndStatusInOrderByCreatedAtAsc(
                batchRunId, List.of(BatchRunItemStatus.PENDING));
        for (BatchRunItem item : pending) {
            item.setStatus(BatchRunItemStatus.CANCELLED);
            item.setCompletedAt(LocalDateTime.now());
            batchRunItemRepository.save(item);
        }
    }

    private ApiDocumentVersion latestOpenApi(UUID projectId) {
        List<ApiDocumentVersion> versions =
                apiDocumentVersionRepository.findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private void attachApiVersion(UUID itemId, ApiDocumentVersion version) {
        if (version == null) return;
        BatchRunItem item = findItem(itemId);
        item.setApiDocumentVersion(version);
        batchRunItemRepository.save(item);
    }

    private void attachRuntime(UUID itemId, UUID runtimeId) {
        if (runtimeId == null) return;
        sourceRuntimeRepository.findById(runtimeId).ifPresent(runtime -> {
            BatchRunItem item = findItem(itemId);
            item.setRuntime(runtime);
            batchRunItemRepository.save(item);
        });
    }

    private void attachTestRun(UUID itemId, UUID testRunId) {
        BatchRunItem item = findItem(itemId);
        TestRun testRun = testRunRepository.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found: " + testRunId));
        item.setTestRun(testRun);
        batchRunItemRepository.save(item);
    }

    private void appendItemWarning(UUID itemId, String warning) {
        BatchRunItem item = findItem(itemId);
        item.setErrorMessage(appendMessage(item.getErrorMessage(), warning));
        batchRunItemRepository.save(item);
    }

    private BatchRun findBatchRun(UUID id) {
        return batchRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BatchRun not found: " + id));
    }

    private BatchRunItem findItem(UUID id) {
        return batchRunItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BatchRunItem not found: " + id));
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

    private SourceRuntimeResponse toRuntimeResponse(SourceRuntime runtime) {
        return SourceRuntimeResponse.builder()
                .id(runtime.getId())
                .projectId(runtime.getSourceProject().getId())
                .runtimeMode(runtime.getRuntimeMode())
                .runtimeStatus(runtime.getRuntimeStatus())
                .runtimeType(runtime.getRuntimeType())
                .publicBaseUrl(runtime.getPublicBaseUrl())
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
