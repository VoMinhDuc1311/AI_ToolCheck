package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.BatchRunOptionsRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.CreateBatchRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.*;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.OpenApiGeneratorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BatchRunServiceImplTest {

    private BatchRunRepository batchRunRepository;
    private BatchRunItemRepository batchRunItemRepository;
    private SourceProjectRepository sourceProjectRepository;
    private ApiDocumentVersionRepository apiDocumentVersionRepository;
    private ApiEndpointRepository apiEndpointRepository;
    private TestCaseRepository testCaseRepository;
    private AiJobLogRepository aiJobLogRepository;
    private SourceRuntimeRepository sourceRuntimeRepository;
    private TestRunRepository testRunRepository;
    private OpenApiGeneratorService openApiGeneratorService;
    private TestCaseService testCaseService;
    private SourceRuntimeService sourceRuntimeService;
    private TestRunService testRunService;
    private BatchRunServiceImpl service;

    private final Map<UUID, BatchRun> batches = new LinkedHashMap<>();
    private final Map<UUID, BatchRunItem> items = new LinkedHashMap<>();
    private final Map<UUID, SourceProject> projects = new LinkedHashMap<>();
    private final Map<UUID, TestRun> testRuns = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        batchRunRepository = mock(BatchRunRepository.class);
        batchRunItemRepository = mock(BatchRunItemRepository.class);
        sourceProjectRepository = mock(SourceProjectRepository.class);
        apiDocumentVersionRepository = mock(ApiDocumentVersionRepository.class);
        apiEndpointRepository = mock(ApiEndpointRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        aiJobLogRepository = mock(AiJobLogRepository.class);
        sourceRuntimeRepository = mock(SourceRuntimeRepository.class);
        testRunRepository = mock(TestRunRepository.class);
        openApiGeneratorService = mock(OpenApiGeneratorService.class);
        testCaseService = mock(TestCaseService.class);
        sourceRuntimeService = mock(SourceRuntimeService.class);
        testRunService = mock(TestRunService.class);

        service = new BatchRunServiceImpl(
                batchRunRepository,
                batchRunItemRepository,
                sourceProjectRepository,
                apiDocumentVersionRepository,
                apiEndpointRepository,
                testCaseRepository,
                aiJobLogRepository,
                sourceRuntimeRepository,
                testRunRepository,
                openApiGeneratorService,
                testCaseService,
                sourceRuntimeService,
                testRunService);

        wireInMemoryRepositories();
    }

    @Test
    void createBatchRun_withValidProjects_createsBatchAndItems() {
        SourceProject p1 = project("P1");
        SourceProject p2 = project("P2");

        var response = service.create(request(List.of(p1.getId(), p2.getId()), defaultOptions()));

        assertThat(response.getStatus()).isEqualTo(BatchRunStatus.PENDING);
        assertThat(response.getTotalItems()).isEqualTo(2);
        assertThat(items.values()).hasSize(2);
    }

    @Test
    void createBatchRun_rejectsEmptyProjectIds() {
        assertThatThrownBy(() -> service.create(request(List.of(), defaultOptions())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("projectIds");
    }

    @Test
    void startBatchRun_processesItemsSequentially() {
        SourceProject p1 = project("P1");
        SourceProject p2 = project("P2");
        UUID batchId = createReadyBatch(List.of(p1, p2), successOptions()).getId();

        service.start(batchId);

        assertThat(items.values()).extracting(BatchRunItem::getStatus)
                .containsExactly(BatchRunItemStatus.SUCCESS, BatchRunItemStatus.SUCCESS);
        verify(testRunService, times(2)).create(any());
    }

    @Test
    void startBatchRun_rejectsAlreadyRunningBatch() {
        SourceProject p1 = project("P1");
        UUID batchId = createReadyBatch(List.of(p1), successOptions()).getId();
        batches.get(batchId).setStatus(BatchRunStatus.RUNNING);

        assertThatThrownBy(() -> service.start(batchId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already RUNNING");
    }

    @Test
    void oneItemFails_batchContinuesNextItem() {
        SourceProject p1 = project("P1");
        SourceProject p2 = project("P2");
        UUID batchId = createReadyBatch(List.of(p1, p2), successOptions()).getId();
        when(testRunService.create(any()))
                .thenThrow(new BadRequestException("boom"))
                .thenReturn(testRunResponse(RunStatus.PENDING));

        var response = service.start(batchId);

        assertThat(response.getStatus()).isEqualTo(BatchRunStatus.PARTIAL);
        assertThat(response.getSuccessCount()).isEqualTo(1);
        assertThat(response.getFailedCount()).isEqualTo(1);
    }

    @Test
    void allSuccess_batchCompleted() {
        SourceProject p1 = project("P1");
        UUID batchId = createReadyBatch(List.of(p1), successOptions()).getId();

        var response = service.start(batchId);

        assertThat(response.getStatus()).isEqualTo(BatchRunStatus.COMPLETED);
    }

    @Test
    void someFail_batchPartial() {
        oneItemFails_batchContinuesNextItem();
    }

    @Test
    void allFail_batchFailed() {
        SourceProject p1 = project("P1");
        UUID batchId = createReadyBatch(List.of(p1), successOptions()).getId();
        when(testRunService.create(any())).thenThrow(new BadRequestException("nope"));

        var response = service.start(batchId);

        assertThat(response.getStatus()).isEqualTo(BatchRunStatus.FAILED);
        assertThat(response.getFailedCount()).isEqualTo(1);
    }

    @Test
    void openApiGenerationFail_marksItemFailedAtGenerateOpenApi() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setGenerateOpenApi(true);
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(apiDocumentVersionRepository.findByApiDocumentSourceProjectIdOrderByVersionNoDesc(p1.getId()))
                .thenReturn(List.of());
        when(openApiGeneratorService.generateAndSaveOpenApi(p1.getId()))
                .thenThrow(new BadRequestException("openapi fail"));

        service.start(batchId);

        BatchRunItem item = items.values().iterator().next();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.FAILED);
        assertThat(item.getCurrentStep()).isEqualTo(BatchRunStep.GENERATE_OPENAPI);
    }

    @Test
    void existingOpenApi_skipsGenerateOpenApiWhenValid() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setGenerateOpenApi(true);
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        existingOpenApi(p1.getId());

        service.start(batchId);

        verify(openApiGeneratorService, never()).generateAndSaveOpenApi(any());
    }

    @Test
    void existingActiveAiCases_doesNotDuplicateGeneratedTestCases() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setGenerateTestCases(true);
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(testCaseRepository.countBySourceProject_IdAndGeneratedByAndActiveFlagTrueAndDeletedFlagFalse(
                p1.getId(), GeneratedBy.AI)).thenReturn(1L);

        service.start(batchId);

        verify(testCaseService, never()).generateTestCaseAsync(any());
    }

    @Test
    void noTestCases_marksItemFailedAtCreateTestRun() {
        SourceProject p1 = project("P1");
        UUID batchId = createReadyBatch(List.of(p1), successOptions()).getId();
        when(testCaseRepository.findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(p1.getId()))
                .thenReturn(List.of());

        service.start(batchId);

        BatchRunItem item = items.values().iterator().next();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.FAILED);
        assertThat(item.getCurrentStep()).isEqualTo(BatchRunStep.CREATE_TEST_RUN);
    }

    @Test
    void runtimeStartFail_marksItemFailedAtStartRuntime() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(RuntimeActionResponse.builder()
                .message("unsupported")
                .runtime(SourceRuntimeResponse.builder().runtimeStatus(RuntimeStatus.ENVIRONMENT_UNSUPPORTED).build())
                .build());

        service.start(batchId);

        BatchRunItem item = items.values().iterator().next();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.FAILED);
        assertThat(item.getCurrentStep()).isEqualTo(BatchRunStep.START_RUNTIME);
    }

    @Test
    void batchRun_passesBuildStrategyToRuntimeStart() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        options.setBuildStrategy(BuildStrategy.AUTO_WITH_FALLBACK);
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO_WITH_FALLBACK)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder()
                        .id(UUID.randomUUID())
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .runtimeStatus(RuntimeStatus.UP)
                        .publicBaseUrl("http://runtime:18080")
                        .build())
                .build());

        service.start(batchId);

        verify(sourceRuntimeService).startRuntime(p1.getId(), BuildStrategy.AUTO_WITH_FALLBACK);
    }

    @Test
    void batchRun_defaultBuildStrategy_isDocumentedAndTested() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder()
                        .id(UUID.randomUUID())
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .runtimeStatus(RuntimeStatus.UP)
                        .publicBaseUrl("http://runtime:18080")
                        .build())
                .build());

        service.start(batchId);

        verify(sourceRuntimeService).startRuntime(p1.getId(), BuildStrategy.AUTO);
    }

    @Test
    void batchRun_pollingDoesNotUseStalePersistenceContext() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID runtimeId = UUID.randomUUID();
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder()
                        .id(runtimeId)
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .runtimeStatus(RuntimeStatus.BUILDING)
                        .build())
                .build());
        when(sourceRuntimeService.waitForRuntimeTerminalState(p1.getId(), runtimeId, 300)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder()
                        .id(runtimeId)
                        .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                        .runtimeStatus(RuntimeStatus.UP)
                        .publicBaseUrl("http://runtime:18080")
                        .build())
                .build());

        service.start(batchId);

        verify(sourceRuntimeService).waitForRuntimeTerminalState(p1.getId(), runtimeId, 300);
        assertThat(items.values().iterator().next().getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
    }

    @Test
    void batchRun_autoWithFallbackRuntimeFailureOrSuccessHandled() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        options.setBuildStrategy(BuildStrategy.AUTO_WITH_FALLBACK);
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO_WITH_FALLBACK)).thenReturn(RuntimeActionResponse.builder()
                .runtime(SourceRuntimeResponse.builder()
                        .runtimeStatus(RuntimeStatus.BUILD_FAILED)
                        .lastError("uploaded and generated dockerfiles failed")
                        .build())
                .build());

        service.start(batchId);

        BatchRunItem item = items.values().iterator().next();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.FAILED);
        assertThat(item.getErrorMessage()).contains("uploaded and generated dockerfiles failed");
    }

    @Test
    void executeFail_marksItemFailedButBatchContinues() {
        SourceProject p1 = project("P1");
        SourceProject p2 = project("P2");
        UUID batchId = createReadyBatch(List.of(p1, p2), successOptions()).getId();
        when(testRunService.execute(any()))
                .thenReturn(testRunResponse(RunStatus.FAILED))
                .thenReturn(testRunResponse(RunStatus.COMPLETED));

        var response = service.start(batchId);

        assertThat(response.getStatus()).isEqualTo(BatchRunStatus.PARTIAL);
        assertThat(response.getFailedCount()).isEqualTo(1);
        assertThat(response.getSuccessCount()).isEqualTo(1);
    }

    @Test
    void stopRuntimeAfterRun_invokesStopRuntime() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStopRuntimeAfterRun(true);
        UUID batchId = createReadyBatch(List.of(p1), options).getId();

        service.start(batchId);

        verify(sourceRuntimeService).stopRuntime(p1.getId());
    }

    @Test
    void cancelBatch_cancelsPendingItems() {
        SourceProject p1 = project("P1");
        UUID batchId = createReadyBatch(List.of(p1), successOptions()).getId();

        var response = service.cancel(batchId);

        assertThat(response.getStatus()).isEqualTo(BatchRunStatus.CANCELLED);
        assertThat(items.values().iterator().next().getStatus()).isEqualTo(BatchRunItemStatus.CANCELLED);
    }

    @Test
    void cancelBatch_attemptsStopRuntimeForRunningItem() {
        SourceProject p1 = project("P1");
        UUID batchId = createReadyBatch(List.of(p1), successOptions()).getId();
        BatchRunItem item = items.values().iterator().next();
        item.setStatus(BatchRunItemStatus.RUNNING);

        service.cancel(batchId);

        verify(sourceRuntimeService).stopRuntime(p1.getId());
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.CANCELLED);
    }

    @Test
    void reportAggregatesCountsCorrectly() {
        SourceProject p1 = project("P1");
        SourceProject p2 = project("P2");
        UUID batchId = createReadyBatch(List.of(p1, p2), successOptions()).getId();
        Iterator<BatchRunItem> iterator = items.values().iterator();
        iterator.next().setStatus(BatchRunItemStatus.SUCCESS);
        iterator.next().setStatus(BatchRunItemStatus.FAILED);
        BatchRun batch = batches.get(batchId);
        batch.setSuccessCount(1);
        batch.setFailedCount(1);
        batch.setStatus(BatchRunStatus.PARTIAL);

        var report = service.getReport(batchId);

        assertThat(report.getSuccessCount()).isEqualTo(1);
        assertThat(report.getFailedCount()).isEqualTo(1);
        assertThat(report.getItems()).hasSize(2);
    }

    @Test
    void noStuckRunningWhenUnexpectedException() {
        SourceProject p1 = project("P1");
        UUID batchId = createReadyBatch(List.of(p1), successOptions()).getId();
        when(testRunService.create(any())).thenThrow(new IllegalStateException("unexpected"));

        service.start(batchId);

        BatchRunItem item = items.values().iterator().next();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.FAILED);
        assertThat(batches.get(batchId).getStatus()).isEqualTo(BatchRunStatus.FAILED);
    }

    private void wireInMemoryRepositories() {
        when(batchRunRepository.save(any(BatchRun.class))).thenAnswer(inv -> {
            BatchRun batch = inv.getArgument(0);
            if (batch.getId() == null) batch.setId(UUID.randomUUID());
            if (batch.getCreatedAt() == null) batch.prePersist(); else batch.preUpdate();
            batches.put(batch.getId(), batch);
            return batch;
        });
        when(batchRunRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(batches.get(inv.getArgument(0))));
        when(batchRunItemRepository.save(any(BatchRunItem.class))).thenAnswer(inv -> {
            BatchRunItem item = inv.getArgument(0);
            if (item.getId() == null) item.setId(UUID.randomUUID());
            if (item.getCreatedAt() == null) item.prePersist(); else item.preUpdate();
            items.put(item.getId(), item);
            return item;
        });
        when(batchRunItemRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(items.get(inv.getArgument(0))));
        when(batchRunItemRepository.findByBatchRun_IdOrderByCreatedAtAsc(any())).thenAnswer(inv -> items.values().stream()
                .filter(item -> item.getBatchRun().getId().equals(inv.getArgument(0)))
                .toList());
        when(batchRunItemRepository.findByBatchRun_IdAndStatusInOrderByCreatedAtAsc(any(), any())).thenAnswer(inv -> {
            UUID batchId = inv.getArgument(0);
            Collection<BatchRunItemStatus> statuses = inv.getArgument(1);
            return items.values().stream()
                    .filter(item -> item.getBatchRun().getId().equals(batchId) && statuses.contains(item.getStatus()))
                    .toList();
        });
        when(batchRunItemRepository.countByBatchRun_IdAndStatus(any(), any())).thenAnswer(inv -> {
            UUID batchId = inv.getArgument(0);
            BatchRunItemStatus status = inv.getArgument(1);
            return items.values().stream()
                    .filter(item -> item.getBatchRun().getId().equals(batchId) && item.getStatus() == status)
                    .count();
        });
        when(sourceProjectRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(projects.get(inv.getArgument(0))));
        when(testRunRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(testRuns.get(inv.getArgument(0))));
    }

    private BatchRun createReadyBatch(List<SourceProject> sourceProjects, BatchRunOptionsRequest options) {
        BatchRun batchRun = BatchRun.builder()
                .name("Batch")
                .status(BatchRunStatus.PENDING)
                .totalItems(sourceProjects.size())
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
                .externalBaseUrl(options.getExternalBaseUrl())
                .build();
        batchRunRepository.save(batchRun);
        for (SourceProject project : sourceProjects) {
            batchRunItemRepository.save(BatchRunItem.builder()
                    .batchRun(batchRun)
                    .sourceProject(project)
                    .status(BatchRunItemStatus.PENDING)
                    .currentStep(BatchRunStep.GENERATE_OPENAPI)
                    .retryCount(0)
                    .maxRetries(options.safeMaxRetries())
                    .build());
            primeProjectForSuccess(project.getId());
        }
        return batchRun;
    }

    private void primeProjectForSuccess(UUID projectId) {
        when(apiDocumentVersionRepository.findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId))
                .thenReturn(List.of());
        when(testCaseRepository.countBySourceProject_IdAndGeneratedByAndActiveFlagTrueAndDeletedFlagFalse(
                projectId, GeneratedBy.AI)).thenReturn(1L);
        when(sourceRuntimeService.resolveBaseUrlForTestRun(eq(projectId), any(), any()))
                .thenReturn("http://external.test");
        when(testCaseRepository.findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(projectId))
                .thenReturn(List.of(testCase(projectId)));
        TestRunDetailResponse created = testRunResponse(RunStatus.PENDING);
        when(testRunService.create(any())).thenReturn(created);
        when(testRunService.execute(any())).thenReturn(testRunResponse(RunStatus.COMPLETED));
    }

    private SourceProject project(String name) {
        SourceProject project = new SourceProject();
        project.setId(UUID.randomUUID());
        project.setProjectName(name);
        project.setDefaultTargetBaseUrl("http://default.test");
        projects.put(project.getId(), project);
        return project;
    }

    private TestCase testCase(UUID projectId) {
        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID());
        testCase.setSourceProject(projects.get(projectId));
        testCase.setActiveFlag(true);
        testCase.setDeletedFlag(false);
        return testCase;
    }

    private TestRunDetailResponse testRunResponse(RunStatus status) {
        UUID id = UUID.randomUUID();
        TestRun run = new TestRun();
        run.setId(id);
        testRuns.put(id, run);
        return TestRunDetailResponse.builder()
                .id(id)
                .runStatus(status)
                .build();
    }

    private void existingOpenApi(UUID projectId) {
        ApiDocumentVersion version = new ApiDocumentVersion();
        version.setId(UUID.randomUUID());
        version.setContentJson("{\"openapi\":\"3.0.0\"}");
        when(apiDocumentVersionRepository.findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId))
                .thenReturn(List.of(version));
    }

    private CreateBatchRunRequest request(List<UUID> projectIds, BatchRunOptionsRequest options) {
        return CreateBatchRunRequest.builder()
                .name("Batch")
                .projectIds(projectIds)
                .options(options)
                .build();
    }

    private BatchRunOptionsRequest defaultOptions() {
        return BatchRunOptionsRequest.builder().build();
    }

    private BatchRunOptionsRequest successOptions() {
        return BatchRunOptionsRequest.builder()
                .generateOpenApi(false)
                .generateTestCases(false)
                .startRuntime(false)
                .executeTestRun(true)
                .stopRuntimeAfterRun(false)
                .maxRetries(1)
                .executionMode(ExecutionMode.READ_ONLY)
                .externalBaseUrl("http://external.test")
                .build();
    }

    // ── HOTFIX: BatchRun runtime polling tests ────────────────────────────────

    @Test
    void batchRun_startRuntime_persistsRuntimeIdOnItemImmediately() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID runtimeId = UUID.randomUUID();
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.BUILDING).build()).build());
        when(sourceRuntimeService.waitForRuntimeTerminalState(p1.getId(), runtimeId, 300)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.UP)
                                .publicBaseUrl("http://52.220.34.212:18080").build()).build());
        when(sourceRuntimeRepository.findById(runtimeId)).thenAnswer(inv -> {
            SourceRuntime rt = new SourceRuntime(); rt.setId(runtimeId);
            return java.util.Optional.of(rt);
        });

        service.start(batchId);

        BatchRunItem item = items.values().iterator().next();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
        assertThat(item.getRuntime()).isNotNull();
        assertThat(item.getRuntime().getId()).isEqualTo(runtimeId);
    }

    @Test
    void batchRun_pollsReturnedRuntimeIdUntilUp() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID runtimeId = UUID.randomUUID();
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.BUILDING).build()).build());
        when(sourceRuntimeService.waitForRuntimeTerminalState(p1.getId(), runtimeId, 300)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.UP)
                                .publicBaseUrl("http://52.220.34.212:18080").build()).build());
        when(sourceRuntimeRepository.findById(runtimeId)).thenAnswer(inv -> {
            SourceRuntime rt = new SourceRuntime(); rt.setId(runtimeId);
            return java.util.Optional.of(rt);
        });

        service.start(batchId);

        verify(sourceRuntimeService).waitForRuntimeTerminalState(p1.getId(), runtimeId, 300);
    }

    @Test
    void batchRun_runtimeBecomesUp_continuesToTestRunStep() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID runtimeId = UUID.randomUUID();
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.BUILDING).build()).build());
        when(sourceRuntimeService.waitForRuntimeTerminalState(p1.getId(), runtimeId, 300)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.UP)
                                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                                .publicBaseUrl("http://52.220.34.212:18080").build()).build());
        when(sourceRuntimeRepository.findById(runtimeId)).thenAnswer(inv -> {
            SourceRuntime rt = new SourceRuntime(); rt.setId(runtimeId);
            return java.util.Optional.of(rt);
        });

        service.start(batchId);

        verify(testRunService).create(any());
        assertThat(items.values().iterator().next().getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
    }

    @Test
    void batchRun_runtimeUp_doesNotTimeout() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID runtimeId = UUID.randomUUID();
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.UP)
                                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                                .publicBaseUrl("http://52.220.34.212:18080").build()).build());
        when(sourceRuntimeRepository.findById(runtimeId)).thenAnswer(inv -> {
            SourceRuntime rt = new SourceRuntime(); rt.setId(runtimeId);
            return java.util.Optional.of(rt);
        });

        service.start(batchId);

        verify(sourceRuntimeService, never()).waitForRuntimeTerminalState(any(), any(), anyInt());
        assertThat(items.values().iterator().next().getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
    }

    @Test
    void batchRun_runtimeTimeoutErrorIncludesRuntimeIdAndLatestStatus() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID runtimeId = UUID.randomUUID();
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.BUILDING).build()).build());
        when(sourceRuntimeService.waitForRuntimeTerminalState(p1.getId(), runtimeId, 300)).thenReturn(
                RuntimeActionResponse.builder()
                        .code("TIMEOUT")
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.BUILDING)
                                .lastHealthStatus("DOWN:timeout").build()).build());

        service.start(batchId);

        BatchRunItem item = items.values().iterator().next();
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.FAILED);
        assertThat(item.getCurrentStep()).isEqualTo(BatchRunStep.START_RUNTIME);
        assertThat(item.getErrorMessage()).contains(runtimeId.toString());
        assertThat(item.getErrorMessage()).contains("latestStatus=BUILDING");
        assertThat(item.getErrorMessage()).contains("300s");
    }

    @Test
    void batchRun_autoWithFallbackPassesBuildStrategyAndRecordsRuntimeId() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        options.setBuildStrategy(BuildStrategy.AUTO_WITH_FALLBACK);
        UUID runtimeId = UUID.randomUUID();
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO_WITH_FALLBACK)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.UP)
                                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                                .publicBaseUrl("http://52.220.34.212:18080").build()).build());
        when(sourceRuntimeRepository.findById(runtimeId)).thenAnswer(inv -> {
            SourceRuntime rt = new SourceRuntime(); rt.setId(runtimeId);
            return java.util.Optional.of(rt);
        });

        service.start(batchId);

        verify(sourceRuntimeService).startRuntime(p1.getId(), BuildStrategy.AUTO_WITH_FALLBACK);
        BatchRunItem item = items.values().iterator().next();
        assertThat(item.getRuntime()).isNotNull();
        assertThat(item.getRuntime().getId()).isEqualTo(runtimeId);
        assertThat(item.getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
    }

    @Test
    void batchRun_doesNotUseStaleRuntimeObjectDuringPolling() {
        SourceProject p1 = project("P1");
        BatchRunOptionsRequest options = successOptions();
        options.setStartRuntime(true);
        UUID runtimeId = UUID.randomUUID();
        UUID batchId = createReadyBatch(List.of(p1), options).getId();
        when(sourceRuntimeService.startRuntime(p1.getId(), BuildStrategy.AUTO)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.BUILDING).build()).build());
        when(sourceRuntimeService.waitForRuntimeTerminalState(p1.getId(), runtimeId, 300)).thenReturn(
                RuntimeActionResponse.builder()
                        .runtime(SourceRuntimeResponse.builder()
                                .id(runtimeId).runtimeStatus(RuntimeStatus.UP)
                                .publicBaseUrl("http://52.220.34.212:18080").build()).build());
        when(sourceRuntimeRepository.findById(runtimeId)).thenAnswer(inv -> {
            SourceRuntime rt = new SourceRuntime(); rt.setId(runtimeId);
            return java.util.Optional.of(rt);
        });

        service.start(batchId);

        verify(sourceRuntimeService).waitForRuntimeTerminalState(p1.getId(), runtimeId, 300);
        assertThat(items.values().iterator().next().getStatus()).isEqualTo(BatchRunItemStatus.SUCCESS);
    }
}
