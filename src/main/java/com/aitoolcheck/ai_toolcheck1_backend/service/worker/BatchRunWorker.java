package com.aitoolcheck.ai_toolcheck1_backend.service.worker;

import com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res.OpenApiGenerateResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeStatusSnapshot;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.*;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.UnauthorizedException;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.security.CustomUserDetails;
import com.aitoolcheck.ai_toolcheck1_backend.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchRunWorker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<UUID>> UUID_LIST_TYPE = new TypeReference<>() {
    };
    private static final Duration AI_JOB_TIMEOUT = Duration.ofMinutes(2);
    private static final int RUNTIME_TIMEOUT_SECONDS = 300;
    private static final long RUNTIME_POLL_INTERVAL_MS = 2_000L;

    private final BatchRunLifecycleService batchLifecycleService;
    private final SourceRuntimeLifecycleService sourceRuntimeLifecycleService;
    private final SourceProjectRepository sourceProjectRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final AiJobLogRepository aiJobLogRepository;
    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final OpenApiGeneratorService openApiGeneratorService;
    private final TestCaseService testCaseService;
    private final SourceRuntimeService sourceRuntimeService;
    private final TestRunService testRunService;
    private final AppUserRepository appUserRepository;

    @Async("batchRunExecutor")
    public void runAsync(UUID batchId) {
        run(batchId);
    }

    public void run(UUID batchId) {
        try {
            List<UUID> itemIds = batchLifecycleService.findItemsFresh(batchId).stream()
                    .map(BatchRunItem::getId)
                    .toList();
            for (UUID itemId : itemIds) {
                BatchRun batch = batchLifecycleService.findBatchFresh(batchId);
                if (batch.getStatus() == BatchRunStatus.CANCELLED) {
                    batchLifecycleService.cancelPendingItems(batchId);
                    break;
                }
                processItem(batchId, itemId);
            }
            BatchRun finished = batchLifecycleService.finalizeBatch(batchId);
            log.info("[BatchRunWorker] batch finished batchId={} status={} success={} failed={}",
                    batchId, finished.getStatus(), finished.getSuccessCount(), finished.getFailedCount());
        } catch (Throwable t) {
            log.error("[BatchRunWorker] batch failed outside item handler batchId={}: {}", batchId, t.getMessage(), t);
            batchLifecycleService.markBatchFailed(batchId, t.getMessage());
        }
    }

    private void processItem(UUID batchId, UUID itemId) {
        BatchRunItem started = batchLifecycleService.markItemRunning(itemId, BatchRunStep.GENERATE_OPENAPI);
        UUID projectId = started.getSourceProject().getId();
        log.info("[BatchRunWorker] item start batchId={} itemId={} projectId={}", batchId, itemId, projectId);
        BatchRunActor actor = null;
        SecurityContext previousContext = SecurityContextHolder.getContext();
        boolean restored = false;
        try {
            BatchRun batch = batchLifecycleService.findBatchFresh(batchId);
            actor = restoreSecurityContext(batch, batchId);
            restored = true;
            SourceProject project = sourceProjectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("SourceProject not found: " + projectId));

            runOpenApiStep(batchId, itemId, projectId);
            runTestCaseStep(batchId, itemId, projectId);
            SourceRuntimeResponse runtime = runRuntimeStep(batchId, itemId, project);
            TestRunDetailResponse testRun = runCreateTestRunStep(batchId, itemId, project, runtime);
            runExecuteTestRunStep(batchId, itemId, testRun);
            runStopRuntimeStep(batchId, itemId, projectId);
            batchLifecycleService.markItemSuccess(itemId);
            log.info("[BatchRunWorker] item success batchId={} itemId={}", batchId, itemId);
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            BatchRunStep currentStep = currentStep(itemId);
            String enriched = failureMessage(message, batchId, itemId, projectId, actor, currentStep);
            batchLifecycleService.markItemFailed(itemId, enriched);
            log.warn("[BatchRunWorker] item failed batchId={} itemId={} projectId={} actorUserId={} actorEmail={} step={} reason={}",
                    batchId, itemId, projectId, actor == null ? null : actor.userId(),
                    actor == null ? null : actor.email(), currentStep, message);
        } finally {
            if (restored) {
                SecurityContextHolder.clearContext();
                log.info("[BatchRunWorker] security context cleared batchId={} itemId={} userId={} email={}",
                        batchId, itemId, actor == null ? null : actor.userId(), actor == null ? null : actor.email());
            }
            if (previousContext != null && previousContext.getAuthentication() != null) {
                SecurityContextHolder.setContext(previousContext);
            }
        }
    }

    private BatchRunActor restoreSecurityContext(BatchRun batchRun, UUID batchId) {
        if (batchRun.getCreatedBy() == null) {
            throw new UnauthorizedException("BatchRun actor is no longer authorized: createdBy is missing. batchId=" + batchId);
        }
        AppUser user = appUserRepository.findById(batchRun.getCreatedBy())
                .orElseThrow(() -> new UnauthorizedException(
                        "BatchRun actor is no longer authorized: user no longer exists. batchId=" + batchId
                                + " userId=" + batchRun.getCreatedBy()));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException(
                    "BatchRun actor is no longer authorized: user is not active. batchId=" + batchId
                            + " userId=" + user.getId()
                            + " email=" + user.getEmail());
        }

        CustomUserDetails userDetails = new CustomUserDetails(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        log.info("[BatchRunWorker] actor resolved batchId={} userId={} email={}",
                batchId, user.getId(), user.getEmail());
        log.info("[BatchRunWorker] security context restored batchId={} userId={} email={}",
                batchId, user.getId(), user.getEmail());
        return new BatchRunActor(user.getId(), user.getEmail());
    }

    private BatchRunStep currentStep(UUID itemId) {
        try {
            BatchRunItem item = batchLifecycleService.findItemFresh(itemId);
            return item.getCurrentStep();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String failureMessage(String message, UUID batchId, UUID itemId, UUID projectId,
                                  BatchRunActor actor, BatchRunStep currentStep) {
        return message
                + " batchId=" + batchId
                + " itemId=" + itemId
                + " projectId=" + projectId
                + " actorUserId=" + (actor == null ? null : actor.userId())
                + " actorEmail=" + (actor == null ? null : actor.email())
                + " currentStep=" + currentStep;
    }

    private ApiDocumentVersion runOpenApiStep(UUID batchId, UUID itemId, UUID projectId) {
        markStep(batchId, itemId, BatchRunStep.GENERATE_OPENAPI);
        BatchRun batchRun = batchLifecycleService.findBatchFresh(batchId);
        ApiDocumentVersion existing = latestOpenApi(projectId);
        if (!batchRun.getGenerateOpenApi()) {
            attachApiVersion(itemId, existing);
            return existing;
        }
        if (existing != null && hasText(existing.getContentJson())) {
            attachApiVersion(itemId, existing);
            return existing;
        }
        OpenApiGenerateResponse response = openApiGeneratorService.generateAndSaveOpenApi(projectId);
        ApiDocumentVersion generated = apiDocumentVersionRepository.findById(response.getApiDocumentVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Generated ApiDocumentVersion not found: " + response.getApiDocumentVersionId()));
        attachApiVersion(itemId, generated);
        return generated;
    }

    private void runTestCaseStep(UUID batchId, UUID itemId, UUID projectId) {
        markStep(batchId, itemId, BatchRunStep.GENERATE_TEST_CASES);
        BatchRun batchRun = batchLifecycleService.findBatchFresh(batchId);
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

    private SourceRuntimeResponse runRuntimeStep(UUID batchId, UUID itemId, SourceProject project) {
        markStep(batchId, itemId, BatchRunStep.START_RUNTIME);
        BatchRun batchRun = batchLifecycleService.findBatchFresh(batchId);
        if (batchRun.getStartRuntime()) {
            BuildStrategy buildStrategy = batchRun.getBuildStrategy();
            RuntimeActionResponse response = sourceRuntimeService.startRuntime(project.getId(), buildStrategy);
            SourceRuntimeResponse runtime = response.getRuntime();
            if (runtime == null || runtime.getId() == null) {
                throw new BadRequestException("Runtime start failed: startRuntime returned no runtime.");
            }
            UUID runtimeId = runtime.getId();
            batchLifecycleService.attachRuntime(itemId, runtimeId);
            log.info("[BatchRunWorker] runtime start accepted batchId={} itemId={} runtimeId={} strategy={} status={}",
                    batchId, itemId, runtimeId, buildStrategy, runtime.getRuntimeStatus());

            RuntimeStatusSnapshot snapshot = waitForRuntimeUp(batchId, itemId, runtimeId);
            log.info("[BatchRunWorker] runtime UP batchId={} itemId={} runtimeId={} publicBaseUrl={}",
                    batchId, itemId, runtimeId, snapshot.getPublicBaseUrl());
            return SourceRuntimeResponse.builder()
                    .id(runtimeId)
                    .projectId(project.getId())
                    .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                    .runtimeStatus(snapshot.getStatus())
                    .publicBaseUrl(snapshot.getPublicBaseUrl())
                    .lastHealthStatus(snapshot.getLastHealthStatus())
                    .lastError(snapshot.getLastError())
                    .updatedAt(snapshot.getUpdatedAt())
                    .build();
        }

        String baseUrl = sourceRuntimeService.resolveBaseUrlForTestRun(
                project.getId(), batchRun.getExternalBaseUrl(), project.getDefaultTargetBaseUrl());
        SourceRuntimeResponse runtime = sourceRuntimeRepository
                .findFirstBySourceProject_IdAndRuntimeStatusOrderByUpdatedAtDesc(project.getId(), RuntimeStatus.UP)
                .map(this::toRuntimeResponse)
                .orElse(SourceRuntimeResponse.builder()
                        .projectId(project.getId())
                        .runtimeStatus(RuntimeStatus.UP)
                        .runtimeMode(RuntimeMode.EXTERNAL_BASE_URL)
                        .publicBaseUrl(baseUrl)
                        .build());
        if (runtime.getId() != null) {
            batchLifecycleService.attachRuntime(itemId, runtime.getId());
        }
        return runtime;
    }

    RuntimeStatusSnapshot waitForRuntimeUp(UUID batchId, UUID itemId, UUID runtimeId) {
        return waitForRuntimeUp(batchId, itemId, runtimeId, RUNTIME_TIMEOUT_SECONDS, RUNTIME_POLL_INTERVAL_MS);
    }

    RuntimeStatusSnapshot waitForRuntimeUp(UUID batchId, UUID itemId, UUID runtimeId, int timeoutSeconds, long pollIntervalMs) {
        long limit = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        RuntimeStatusSnapshot latest = null;
        while (System.currentTimeMillis() < limit) {
            latest = sourceRuntimeLifecycleService.findStatusSnapshot(runtimeId);
            log.info("[BatchRunWorker] runtime poll batchId={} itemId={} runtimeId={} status={} publicBaseUrl={}",
                    batchId, itemId, runtimeId, latest.getStatus(), latest.getPublicBaseUrl());
            if (latest.getStatus() == RuntimeStatus.UP && hasText(latest.getPublicBaseUrl())) {
                return latest;
            }
            if (isTerminalFailure(latest.getStatus())) {
                throw new BadRequestException("Runtime start failed: " + runtimeDiagnostic(
                        "Runtime reached terminal failure status.", batchId, itemId, latest));
            }
            sleep(pollIntervalMs);
        }
        if (latest == null) {
            latest = sourceRuntimeLifecycleService.findStatusSnapshot(runtimeId);
        }
        throw new BadRequestException("Runtime start failed: " + runtimeDiagnostic(
                "Runtime failed to reach UP after " + timeoutSeconds + "s.", batchId, itemId, latest));
    }

    private TestRunDetailResponse runCreateTestRunStep(UUID batchId, UUID itemId, SourceProject project, SourceRuntimeResponse runtime) {
        markStep(batchId, itemId, BatchRunStep.CREATE_TEST_RUN);
        BatchRun batchRun = batchLifecycleService.findBatchFresh(batchId);
        List<UUID> selectedTestCaseIds = decodeTestCaseIds(batchRun.getTestCaseIdsJson());
        boolean hasExplicitSelection = !selectedTestCaseIds.isEmpty();
        if (!hasExplicitSelection && testCaseRepository
                .findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(project.getId())
                .isEmpty()) {
            throw new BadRequestException("No active testcases found for project: " + project.getId());
        }
        String baseUrl = batchRun.getStartRuntime() ? null : batchRun.getExternalBaseUrl();
        RuntimeMode runtimeMode = batchRun.getStartRuntime()
                ? RuntimeMode.AUTO_RUNTIME_FROM_SOURCE
                : (runtime == null ? null : runtime.getRuntimeMode());
        TestRunDetailResponse response = testRunService.create(CreateTestRunRequest.builder()
                .projectId(project.getId())
                .runName(batchRun.getName() + " - " + project.getProjectName())
                .description("BatchRun " + batchRun.getId() + " item " + itemId)
                .baseUrl(baseUrl)
                .executionMode(batchRun.getExecutionMode())
                .runtimeMode(runtimeMode)
                .testCaseIds(hasExplicitSelection ? selectedTestCaseIds : null)
                .includeAllActive(!hasExplicitSelection)
                .build());
        batchLifecycleService.attachTestRun(itemId, response.getId());
        log.info("[BatchRunWorker] testRun created batchId={} itemId={} testRunId={} explicitTestCaseCount={}",
                batchId, itemId, response.getId(), hasExplicitSelection ? selectedTestCaseIds.size() : null);
        return response;
    }

    private void runExecuteTestRunStep(UUID batchId, UUID itemId, TestRunDetailResponse testRun) {
        markStep(batchId, itemId, BatchRunStep.EXECUTE_TEST_RUN);
        BatchRun batchRun = batchLifecycleService.findBatchFresh(batchId);
        if (!batchRun.getExecuteTestRun()) {
            return;
        }
        TestRunDetailResponse executed = testRunService.execute(testRun.getId());
        log.info("[BatchRunWorker] testRun executed batchId={} itemId={} testRunId={} status={}",
                batchId, itemId, testRun.getId(), executed.getRunStatus());
        if (executed.getRunStatus() == RunStatus.FAILED) {
            throw new BadRequestException("TestRun execution failed: " + executed.getId());
        }
    }

    private void runStopRuntimeStep(UUID batchId, UUID itemId, UUID projectId) {
        markStep(batchId, itemId, BatchRunStep.STOP_RUNTIME);
        BatchRun batchRun = batchLifecycleService.findBatchFresh(batchId);
        if (!batchRun.getStopRuntimeAfterRun()) {
            return;
        }
        try {
            sourceRuntimeService.stopRuntime(projectId);
        } catch (Exception e) {
            batchLifecycleService.appendItemWarning(itemId, "stopRuntime failed: " + e.getMessage());
        }
    }

    private void markStep(UUID batchId, UUID itemId, BatchRunStep step) {
        batchLifecycleService.markItemStep(itemId, step);
        log.info("[BatchRunWorker] step batchId={} itemId={} step={}", batchId, itemId, step);
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
            sleep(1_000L);
        }
        throw new BadRequestException("Timed out waiting for AI testcase generation.");
    }

    private ApiDocumentVersion latestOpenApi(UUID projectId) {
        List<ApiDocumentVersion> versions =
                apiDocumentVersionRepository.findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private void attachApiVersion(UUID itemId, ApiDocumentVersion version) {
        if (version != null) {
            batchLifecycleService.attachApiVersion(itemId, version.getId());
        }
    }

    private boolean isTerminalFailure(RuntimeStatus status) {
        return status == RuntimeStatus.BUILD_FAILED
                || status == RuntimeStatus.START_FAILED
                || status == RuntimeStatus.UNHEALTHY
                || status == RuntimeStatus.ENVIRONMENT_UNSUPPORTED
                || status == RuntimeStatus.STOPPED;
    }

    private String runtimeDiagnostic(String prefix, UUID batchId, UUID itemId, RuntimeStatusSnapshot snapshot) {
        return prefix
                + " runtimeId=" + snapshot.getRuntimeId()
                + " latestStatus=" + snapshot.getStatus()
                + " publicBaseUrl=" + snapshot.getPublicBaseUrl()
                + " lastHealthStatus=" + snapshot.getLastHealthStatus()
                + " lastError=" + snapshot.getLastError()
                + " updatedAt=" + snapshot.getUpdatedAt()
                + " batchId=" + batchId
                + " itemId=" + itemId;
    }

    private SourceRuntimeResponse toRuntimeResponse(SourceRuntime runtime) {
        return SourceRuntimeResponse.builder()
                .id(runtime.getId())
                .projectId(runtime.getSourceProject().getId())
                .runtimeMode(runtime.getRuntimeMode())
                .runtimeStatus(runtime.getRuntimeStatus())
                .publicBaseUrl(runtime.getPublicBaseUrl())
                .build();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("Interrupted while waiting for background operation.");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<UUID> decodeTestCaseIds(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, UUID_LIST_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("Invalid BatchRun testCaseIdsJson: " + e.getMessage());
        }
    }

    private record BatchRunActor(UUID userId, String email) {
    }
}
