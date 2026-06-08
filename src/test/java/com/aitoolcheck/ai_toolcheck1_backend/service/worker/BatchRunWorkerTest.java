package com.aitoolcheck.ai_toolcheck1_backend.service.worker;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeStatusSnapshot;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.*;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ForbiddenException;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchRunWorkerTest {

    private BatchRunLifecycleService lifecycle;
    private SourceRuntimeLifecycleService runtimeLifecycle;
    private SourceProjectRepository sourceProjectRepository;
    private TestCaseRepository testCaseRepository;
    private SourceRuntimeRepository sourceRuntimeRepository;
    private SourceRuntimeService sourceRuntimeService;
    private TestRunService testRunService;
    private AppUserRepository appUserRepository;
    private BatchRunWorker worker;
    private AppUser actor;

    private final Map<UUID, BatchRun> batches = new LinkedHashMap<>();
    private final Map<UUID, BatchRunItem> items = new LinkedHashMap<>();
    private final Map<UUID, SourceProject> projects = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        lifecycle = mock(BatchRunLifecycleService.class);
        runtimeLifecycle = mock(SourceRuntimeLifecycleService.class);
        sourceProjectRepository = mock(SourceProjectRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        sourceRuntimeRepository = mock(SourceRuntimeRepository.class);
        sourceRuntimeService = mock(SourceRuntimeService.class);
        testRunService = mock(TestRunService.class);
        appUserRepository = mock(AppUserRepository.class);
        actor = AppUser.builder()
                .id(UUID.randomUUID())
                .email("batch.actor@example.com")
                .passwordHash("hash")
                .role(UserRole.MEMBER)
                .status(UserStatus.ACTIVE)
                .build();
        when(appUserRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        SecurityContextHolder.clearContext();

        worker = new BatchRunWorker(
                lifecycle,
                runtimeLifecycle,
                sourceProjectRepository,
                mock(ApiDocumentVersionRepository.class),
                mock(ApiEndpointRepository.class),
                testCaseRepository,
                mock(AiJobLogRepository.class),
                sourceRuntimeRepository,
                mock(OpenApiGeneratorService.class),
                mock(TestCaseService.class),
                sourceRuntimeService,
                testRunService,
                appUserRepository);

        wireLifecycle();
    }

    @Test
    void batchWorker_processesSingleItemToSuccess() {
        UUID batchId = readyBatch(List.of(project("P1")), options(true)).getId();
        UUID runtimeId = primeRuntimeStart(projects.values().iterator().next().getId(), RuntimeStatus.UP);

        worker.run(batchId);

        BatchRunItem item = firstItem();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
        assertThat(item.getRuntime().getId()).isEqualTo(runtimeId);
        assertThat(item.getTestRun()).isNotNull();
        assertThat(batches.get(batchId).getStatus()).isEqualTo(BatchRunStatus.COMPLETED);
    }

    @Test
    void batchWorker_startRuntimePersistsRuntimeIdImmediately() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        UUID runtimeId = primeRuntimeStart(p1.getId(), RuntimeStatus.UP);

        worker.run(batchId);

        verify(lifecycle, atLeastOnce()).attachRuntime(firstItem().getId(), runtimeId);
        assertThat(firstItem().getRuntime().getId()).isEqualTo(runtimeId);
    }

    @Test
    void batchWorker_pollsExactRuntimeIdUntilUp() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        UUID runtimeId = primeRuntimeStart(p1.getId(), RuntimeStatus.UP);

        worker.run(batchId);

        verify(runtimeLifecycle, atLeastOnce()).findStatusSnapshot(runtimeId);
    }

    @Test
    void batchWorker_runtimeUpContinuesToCreateTestRun() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        primeRuntimeStart(p1.getId(), RuntimeStatus.UP);

        worker.run(batchId);

        verify(testRunService).create(any(CreateTestRunRequest.class));
    }

    @Test
    void batchWorker_resolvesActorBeforeStartRuntime() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        primeRuntimeStart(p1.getId(), RuntimeStatus.UP);

        worker.run(batchId);

        verify(appUserRepository, atLeastOnce()).findById(actor.getId());
        verify(sourceRuntimeService).startRuntime(p1.getId(), BuildStrategy.AUTO);
    }

    @Test
    void batchWorker_startRuntimeHasAuthenticationContext() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        UUID runtimeId = UUID.randomUUID();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenAnswer(inv -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(actor.getEmail());
            return RuntimeActionResponse.builder()
                    .runtime(SourceRuntimeResponse.builder()
                            .id(runtimeId)
                            .runtimeStatus(RuntimeStatus.UP)
                            .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                            .publicBaseUrl("http://runtime")
                            .build())
                    .build();
        });
        when(runtimeLifecycle.findStatusSnapshot(runtimeId)).thenReturn(snapshot(runtimeId, RuntimeStatus.UP, "http://runtime"));

        worker.run(batchId);

        assertThat(firstItem().getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
    }

    @Test
    void batchWorker_clearsSecurityContextAfterRun() {
        UUID batchId = readyBatch(List.of(project("P1")), options(false)).getId();

        worker.run(batchId);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void batchWorker_missingActorFailsItemWithClearError() {
        BatchRun batch = readyBatch(List.of(project("P1")), options(true));
        batch.setCreatedBy(null);

        worker.run(batch.getId());

        BatchRunItem item = firstItem();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.FAILED);
        assertThat(item.getErrorMessage())
                .contains("BatchRun actor is no longer authorized")
                .contains("createdBy is missing")
                .contains("batchId=" + batch.getId())
                .contains("itemId=" + item.getId())
                .contains("projectId=" + item.getSourceProject().getId())
                .contains("currentStep=");
        verify(sourceRuntimeService, never()).startRuntime(any(), any());
    }

    @Test
    void batchWorker_actorWithoutProjectPermissionFailsClearly() {
        SourceProject p1 = project("P1");
        BatchRun batch = readyBatch(List.of(p1), options(true));
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO))
                .thenThrow(new ForbiddenException("You do not have permission to perform this action"));

        worker.run(batch.getId());

        BatchRunItem item = firstItem();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.FAILED);
        assertThat(item.getErrorMessage())
                .contains("You do not have permission")
                .contains("actorUserId=" + actor.getId())
                .contains("actorEmail=" + actor.getEmail())
                .contains("currentStep=START_RUNTIME");
    }

    @Test
    void batchWorker_asyncThreadWithoutHttpRequest_doesNotThrowAuthenticationRequired() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        primeRuntimeStart(p1.getId(), RuntimeStatus.UP);
        SecurityContextHolder.clearContext();

        worker.run(batchId);

        assertThat(firstItem().getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
        assertThat(firstItem().getErrorMessage()).isNull();
    }

    @Test
    void batchWorker_persistsTestRunIdAfterCreate() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        primeRuntimeStart(p1.getId(), RuntimeStatus.UP);
        UUID testRunId = UUID.randomUUID();
        when(testRunService.create(any())).thenReturn(TestRunDetailResponse.builder().id(testRunId).runStatus(RunStatus.PENDING).build());

        worker.run(batchId);

        verify(lifecycle).attachTestRun(firstItem().getId(), testRunId);
        assertThat(firstItem().getTestRun().getId()).isEqualTo(testRunId);
    }

    @Test
    void batchWorker_executeTestRunSuccessMarksItemSuccess() {
        UUID batchId = readyBatch(List.of(project("P1")), options(false)).getId();

        worker.run(batchId);

        verify(testRunService).execute(any());
        assertThat(firstItem().getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
    }

    @Test
    void batchWorker_stopRuntimeAfterRunStopsRuntime() {
        SourceProject p1 = project("P1");
        BatchRun batch = readyBatch(List.of(p1), options(false));
        batch.setStopRuntimeAfterRun(true);

        worker.run(batch.getId());

        verify(sourceRuntimeService).stopRuntime(p1.getId());
    }

    @Test
    void batchWorker_itemFailureContinuesNextItem() {
        SourceProject p1 = project("P1");
        SourceProject p2 = project("P2");
        UUID batchId = readyBatch(List.of(p1, p2), options(false)).getId();
        when(testRunService.create(any()))
                .thenThrow(new BadRequestException("boom"))
                .thenReturn(testRun(UUID.randomUUID(), RunStatus.PENDING));

        worker.run(batchId);

        assertThat(new ArrayList<>(items.values())).extracting(BatchRunItem::getStatus)
                .containsExactly(BatchRunItemStatus.FAILED, BatchRunItemStatus.SUCCESS);
    }

    @Test
    void batchWorker_allSuccessBatchCompleted() {
        UUID batchId = readyBatch(List.of(project("P1")), options(false)).getId();

        worker.run(batchId);

        assertThat(batches.get(batchId).getStatus()).isEqualTo(BatchRunStatus.COMPLETED);
    }

    @Test
    void batchWorker_allFailedBatchFailed() {
        UUID batchId = readyBatch(List.of(project("P1")), options(false)).getId();
        when(testRunService.create(any())).thenThrow(new BadRequestException("no tests"));

        worker.run(batchId);

        assertThat(batches.get(batchId).getStatus()).isEqualTo(BatchRunStatus.FAILED);
    }

    @Test
    void batchWorker_mixedBatchPartial() {
        SourceProject p1 = project("P1");
        SourceProject p2 = project("P2");
        UUID batchId = readyBatch(List.of(p1, p2), options(false)).getId();
        when(testRunService.create(any()))
                .thenThrow(new BadRequestException("boom"))
                .thenReturn(testRun(UUID.randomUUID(), RunStatus.PENDING));

        worker.run(batchId);

        assertThat(batches.get(batchId).getStatus()).isEqualTo(BatchRunStatus.PARTIAL);
    }

    @Test
    void batchRuntimePoll_usesFreshSnapshotReads() {
        UUID runtimeId = UUID.randomUUID();
        when(runtimeLifecycle.findStatusSnapshot(runtimeId)).thenReturn(snapshot(runtimeId, RuntimeStatus.UP, "http://runtime"));

        worker.waitForRuntimeUp(UUID.randomUUID(), UUID.randomUUID(), runtimeId, 1, 0);

        verify(runtimeLifecycle).findStatusSnapshot(runtimeId);
    }

    @Test
    void batchRuntimePoll_doesNotUseInitialBuildingResponse() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        UUID runtimeId = UUID.randomUUID();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder().id(runtimeId).runtimeStatus(RuntimeStatus.BUILDING).build())
                .build());
        when(runtimeLifecycle.findStatusSnapshot(runtimeId)).thenReturn(snapshot(runtimeId, RuntimeStatus.UP, "http://runtime"));

        worker.run(batchId);

        assertThat(firstItem().getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
        verify(testRunService).create(any());
    }

    @Test
    void batchRuntimePoll_seesBuildingThenUpFromSeparateTransaction() {
        UUID runtimeId = UUID.randomUUID();
        when(runtimeLifecycle.findStatusSnapshot(runtimeId))
                .thenReturn(snapshot(runtimeId, RuntimeStatus.BUILDING, null))
                .thenReturn(snapshot(runtimeId, RuntimeStatus.UP, "http://runtime"));

        RuntimeStatusSnapshot result = worker.waitForRuntimeUp(UUID.randomUUID(), UUID.randomUUID(), runtimeId, 3, 1);

        assertThat(result.getStatus()).isEqualTo(RuntimeStatus.UP);
        verify(runtimeLifecycle, times(2)).findStatusSnapshot(runtimeId);
    }

    @Test
    void batchRuntimePoll_timeoutIncludesRuntimeDiagnostics() {
        UUID batchId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        when(runtimeLifecycle.findStatusSnapshot(runtimeId))
                .thenReturn(snapshot(runtimeId, RuntimeStatus.BUILDING, null));

        assertThatThrownBy(() -> worker.waitForRuntimeUp(batchId, itemId, runtimeId, 0, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(runtimeId.toString())
                .hasMessageContaining("latestStatus=BUILDING")
                .hasMessageContaining("publicBaseUrl=null")
                .hasMessageContaining("batchId=" + batchId)
                .hasMessageContaining("itemId=" + itemId);
    }

    @Test
    void batchRuntimePoll_terminalFailureFailsImmediately() {
        UUID runtimeId = UUID.randomUUID();
        when(runtimeLifecycle.findStatusSnapshot(runtimeId))
                .thenReturn(snapshot(runtimeId, RuntimeStatus.BUILD_FAILED, null));

        assertThatThrownBy(() -> worker.waitForRuntimeUp(UUID.randomUUID(), UUID.randomUUID(), runtimeId, 10, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("terminal failure")
                .hasMessageContaining("latestStatus=BUILD_FAILED");
        verify(runtimeLifecycle).findStatusSnapshot(runtimeId);
    }

    @Test
    void batchWorker_passesAutoWithFallbackToStartRuntime() {
        SourceProject p1 = project("P1");
        BatchRun batch = readyBatch(List.of(p1), options(true));
        batch.setBuildStrategy(BuildStrategy.AUTO_WITH_FALLBACK);
        primeRuntimeStart(p1.getId(), RuntimeStatus.UP);

        worker.run(batch.getId());

        verify(sourceRuntimeService).startRuntime(p1.getId(), BuildStrategy.AUTO_WITH_FALLBACK);
    }

    @Test
    void productionRegression_buildingStartThenUpSnapshotDoesNotTimeoutAtStartRuntime() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        UUID runtimeId = UUID.randomUUID();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder()
                        .id(runtimeId)
                        .runtimeStatus(RuntimeStatus.BUILDING)
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .build())
                .build());
        when(runtimeLifecycle.findStatusSnapshot(runtimeId))
                .thenReturn(snapshot(runtimeId, RuntimeStatus.BUILDING, null))
                .thenReturn(snapshot(runtimeId, RuntimeStatus.UP, "http://52.220.34.212:18080"));

        worker.run(batchId);

        BatchRunItem item = firstItem();
        assertThat(item.getRuntime().getId()).isEqualTo(runtimeId);
        assertThat(item.getTestRun()).isNotNull();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
        assertThat(batches.get(batchId).getStatus()).isEqualTo(BatchRunStatus.COMPLETED);
    }

    @Test
    void batchWorker_createTestRunUsesAutoRuntimeModeWhenRuntimeStarted() {
        SourceProject p1 = project("P1");
        UUID batchId = readyBatch(List.of(p1), options(true)).getId();
        primeRuntimeStart(p1.getId(), RuntimeStatus.UP);
        ArgumentCaptor<CreateTestRunRequest> captor = ArgumentCaptor.forClass(CreateTestRunRequest.class);

        worker.run(batchId);

        verify(testRunService).create(captor.capture());
        assertThat(captor.getValue().getRuntimeMode()).isEqualTo(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE);
        assertThat(captor.getValue().getBaseUrl()).isNull();
    }

    private void wireLifecycle() {
        when(lifecycle.findBatchFresh(any())).thenAnswer(inv -> batches.get(inv.getArgument(0)));
        when(lifecycle.findItemFresh(any())).thenAnswer(inv -> items.get(inv.getArgument(0)));
        when(lifecycle.findItemsFresh(any())).thenAnswer(inv -> items.values().stream()
                .filter(item -> item.getBatchRun().getId().equals(inv.getArgument(0)))
                .toList());
        when(lifecycle.markItemRunning(any(), any())).thenAnswer(inv -> {
            BatchRunItem item = items.get(inv.getArgument(0));
            item.setStatus(BatchRunItemStatus.RUNNING);
            item.setCurrentStep(inv.getArgument(1));
            return item;
        });
        when(lifecycle.markItemStep(any(), any())).thenAnswer(inv -> {
            BatchRunItem item = items.get(inv.getArgument(0));
            item.setCurrentStep(inv.getArgument(1));
            return item;
        });
        when(lifecycle.attachRuntime(any(), any())).thenAnswer(inv -> {
            BatchRunItem item = items.get(inv.getArgument(0));
            SourceRuntime runtime = new SourceRuntime();
            runtime.setId(inv.getArgument(1));
            item.setRuntime(runtime);
            return item;
        });
        when(lifecycle.attachTestRun(any(), any())).thenAnswer(inv -> {
            BatchRunItem item = items.get(inv.getArgument(0));
            TestRun testRun = new TestRun();
            testRun.setId(inv.getArgument(1));
            item.setTestRun(testRun);
            return item;
        });
        when(lifecycle.markItemSuccess(any())).thenAnswer(inv -> {
            BatchRunItem item = items.get(inv.getArgument(0));
            item.setStatus(BatchRunItemStatus.SUCCESS);
            item.setCurrentStep(BatchRunStep.DONE);
            return item;
        });
        when(lifecycle.markItemFailed(any(), anyString())).thenAnswer(inv -> {
            BatchRunItem item = items.get(inv.getArgument(0));
            item.setStatus(BatchRunItemStatus.FAILED);
            item.setErrorMessage(inv.getArgument(1));
            return item;
        });
        when(lifecycle.finalizeBatch(any())).thenAnswer(inv -> finalizeBatch(batches.get(inv.getArgument(0))));
        when(sourceProjectRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(projects.get(inv.getArgument(0))));
        when(testRunService.create(any())).thenReturn(testRun(UUID.randomUUID(), RunStatus.PENDING));
        when(testRunService.execute(any())).thenAnswer(inv -> testRun(inv.getArgument(0), RunStatus.COMPLETED));
    }

    private UUID primeRuntimeStart(UUID projectId, RuntimeStatus finalStatus) {
        UUID runtimeId = UUID.randomUUID();
        when(sourceRuntimeService.startRuntime(projectId, BuildStrategy.AUTO)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder()
                        .id(runtimeId)
                        .runtimeStatus(finalStatus == RuntimeStatus.UP ? RuntimeStatus.UP : RuntimeStatus.BUILDING)
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .publicBaseUrl(finalStatus == RuntimeStatus.UP ? "http://runtime" : null)
                        .build())
                .build());
        when(sourceRuntimeService.startRuntime(projectId, BuildStrategy.AUTO_WITH_FALLBACK)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder()
                        .id(runtimeId)
                        .runtimeStatus(RuntimeStatus.UP)
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .publicBaseUrl("http://runtime")
                        .build())
                .build());
        when(runtimeLifecycle.findStatusSnapshot(runtimeId)).thenReturn(snapshot(runtimeId, finalStatus, "http://runtime"));
        return runtimeId;
    }

    private BatchRun readyBatch(List<SourceProject> sourceProjects, BatchRunOptions options) {
        BatchRun batch = BatchRun.builder()
                .id(UUID.randomUUID())
                .name("Batch")
                .status(BatchRunStatus.RUNNING)
                .totalItems(sourceProjects.size())
                .successCount(0)
                .failedCount(0)
                .skippedCount(0)
                .runningCount(0)
                .generateOpenApi(false)
                .generateTestCases(false)
                .startRuntime(options.startRuntime)
                .executeTestRun(true)
                .stopRuntimeAfterRun(false)
                .maxConcurrency(1)
                .maxRetries(1)
                .executionMode(ExecutionMode.READ_ONLY)
                .buildStrategy(BuildStrategy.AUTO)
                .externalBaseUrl("http://external.test")
                .createdBy(actor.getId())
                .build();
        batches.put(batch.getId(), batch);
        for (SourceProject project : sourceProjects) {
            BatchRunItem item = BatchRunItem.builder()
                    .id(UUID.randomUUID())
                    .batchRun(batch)
                    .sourceProject(project)
                    .status(BatchRunItemStatus.PENDING)
                    .currentStep(BatchRunStep.GENERATE_OPENAPI)
                    .retryCount(0)
                    .maxRetries(1)
                    .build();
            items.put(item.getId(), item);
            when(testCaseRepository.findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(project.getId()))
                    .thenReturn(List.of(testCase(project)));
        }
        return batch;
    }

    private BatchRun finalizeBatch(BatchRun batch) {
        long success = items.values().stream()
                .filter(item -> item.getBatchRun().getId().equals(batch.getId()) && item.getStatus() == BatchRunItemStatus.SUCCESS)
                .count();
        long failed = items.values().stream()
                .filter(item -> item.getBatchRun().getId().equals(batch.getId()) && item.getStatus() == BatchRunItemStatus.FAILED)
                .count();
        batch.setSuccessCount((int) success);
        batch.setFailedCount((int) failed);
        batch.setRunningCount(0);
        if (success > 0 && failed == 0) {
            batch.setStatus(BatchRunStatus.COMPLETED);
        } else if (success > 0) {
            batch.setStatus(BatchRunStatus.PARTIAL);
        } else {
            batch.setStatus(BatchRunStatus.FAILED);
        }
        return batch;
    }

    private SourceProject project(String name) {
        SourceProject project = new SourceProject();
        project.setId(UUID.randomUUID());
        project.setProjectName(name);
        project.setDefaultTargetBaseUrl("http://default.test");
        projects.put(project.getId(), project);
        when(sourceRuntimeService.resolveBaseUrlForTestRun(eq(project.getId()), any(), any()))
                .thenReturn("http://external.test");
        return project;
    }

    private TestCase testCase(SourceProject project) {
        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID());
        testCase.setSourceProject(project);
        testCase.setActiveFlag(true);
        testCase.setDeletedFlag(false);
        return testCase;
    }

    private TestRunDetailResponse testRun(UUID id, RunStatus status) {
        return TestRunDetailResponse.builder().id(id).runStatus(status).build();
    }

    private RuntimeStatusSnapshot snapshot(UUID runtimeId, RuntimeStatus status, String publicBaseUrl) {
        return RuntimeStatusSnapshot.builder()
                .runtimeId(runtimeId)
                .status(status)
                .publicBaseUrl(publicBaseUrl)
                .lastHealthStatus(status == RuntimeStatus.UP ? "UP:/health:200" : null)
                .lastError(status == RuntimeStatus.BUILD_FAILED ? "build failed" : null)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private BatchRunItem firstItem() {
        return items.values().iterator().next();
    }

    private BatchRunOptions options(boolean startRuntime) {
        return new BatchRunOptions(startRuntime);
    }

    private record BatchRunOptions(boolean startRuntime) {
    }
}
