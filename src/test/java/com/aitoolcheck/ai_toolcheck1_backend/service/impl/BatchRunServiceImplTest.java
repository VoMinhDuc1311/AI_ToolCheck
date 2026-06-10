package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.BatchRunOptionsRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.CreateBatchRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.enums.*;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRun;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.worker.BatchRunWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BatchRunServiceImplTest {

    private BatchRunRepository batchRunRepository;
    private BatchRunItemRepository batchRunItemRepository;
    private SourceProjectRepository sourceProjectRepository;
    private TestCaseRepository testCaseRepository;
    private BatchRunLifecycleService batchRunLifecycleService;
    private BatchRunWorker batchRunWorker;
    private CurrentUserService currentUserService;
    private BatchRunServiceImpl service;
    private AppUser currentUser;

    private final Map<UUID, BatchRun> batches = new LinkedHashMap<>();
    private final Map<UUID, BatchRunItem> items = new LinkedHashMap<>();
    private final Map<UUID, SourceProject> projects = new LinkedHashMap<>();
    private final Map<UUID, TestCase> testCases = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        batchRunRepository = mock(BatchRunRepository.class);
        batchRunItemRepository = mock(BatchRunItemRepository.class);
        sourceProjectRepository = mock(SourceProjectRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        batchRunLifecycleService = mock(BatchRunLifecycleService.class);
        batchRunWorker = mock(BatchRunWorker.class);
        currentUserService = mock(CurrentUserService.class);
        currentUser = AppUser.builder()
                .id(UUID.randomUUID())
                .email("starter@example.com")
                .role(UserRole.MEMBER)
                .status(UserStatus.ACTIVE)
                .passwordHash("hash")
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(currentUser);

        service = new BatchRunServiceImpl(
                batchRunRepository,
                batchRunItemRepository,
                sourceProjectRepository,
                testCaseRepository,
                mock(SourceRuntimeService.class),
                batchRunLifecycleService,
                batchRunWorker,
                currentUserService);

        wireRepositories();
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
    void createBatchRun_persistsCreatedByUser() {
        SourceProject p1 = project("P1");

        var response = service.create(request(List.of(p1.getId()), defaultOptions()));

        assertThat(response.getCreatedBy()).isEqualTo(currentUser.getId());
        assertThat(batches.get(response.getId()).getCreatedBy()).isEqualTo(currentUser.getId());
    }

    @Test
    void createBatchRun_withTestCaseIds_persistsSelection() {
        SourceProject p1 = project("P1");
        TestCase tc = testCase(p1, true, false, false);
        BatchRunOptionsRequest options = successOptions();
        options.setTestCaseIds(List.of(tc.getId()));

        var response = service.create(request(List.of(p1.getId()), options));

        assertThat(response.getTestCaseIds()).containsExactly(tc.getId());
        assertThat(batches.get(response.getId()).getTestCaseIdsJson()).contains(tc.getId().toString());
    }

    @Test
    void createBatchRun_withoutTestCaseIds_keepsExistingBehavior() {
        SourceProject p1 = project("P1");

        var response = service.create(request(List.of(p1.getId()), successOptions()));

        assertThat(response.getTestCaseIds()).isEmpty();
        assertThat(batches.get(response.getId()).getTestCaseIdsJson()).isNull();
    }

    @Test
    void batchRun_rejectsTestCaseFromOtherProject() {
        SourceProject p1 = project("P1");
        SourceProject p2 = project("P2");
        TestCase tc = testCase(p2, true, false, false);
        BatchRunOptionsRequest options = successOptions();
        options.setTestCaseIds(List.of(tc.getId()));

        assertThatThrownBy(() -> service.create(request(List.of(p1.getId()), options)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("testCaseId=" + tc.getId())
                .hasMessageContaining("projectId=" + p1.getId())
                .hasMessageContaining("belongs to another project");
    }

    @Test
    void batchRun_rejectsInactiveTestCase() {
        SourceProject p1 = project("P1");
        TestCase tc = testCase(p1, false, false, false);
        BatchRunOptionsRequest options = successOptions();
        options.setTestCaseIds(List.of(tc.getId()));

        assertThatThrownBy(() -> service.create(request(List.of(p1.getId()), options)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("testCaseId=" + tc.getId())
                .hasMessageContaining("not active");
    }

    @Test
    void batchRun_rejectsDeletedTestCase() {
        SourceProject p1 = project("P1");
        TestCase tc = testCase(p1, true, true, false);
        BatchRunOptionsRequest options = successOptions();
        options.setTestCaseIds(List.of(tc.getId()));

        assertThatThrownBy(() -> service.create(request(List.of(p1.getId()), options)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("testCaseId=" + tc.getId())
                .hasMessageContaining("deleted");
    }

    @Test
    void batchRun_readOnlyRejectsRequiresWriteTestCase() {
        SourceProject p1 = project("P1");
        TestCase tc = testCase(p1, true, false, true);
        BatchRunOptionsRequest options = successOptions();
        options.setTestCaseIds(List.of(tc.getId()));

        assertThatThrownBy(() -> service.create(request(List.of(p1.getId()), options)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("testCaseId=" + tc.getId())
                .hasMessageContaining("write testcase not allowed in READ_ONLY");
    }

    @Test
    void createBatchRun_rejectsEmptyProjectIds() {
        assertThatThrownBy(() -> service.create(request(List.of(), defaultOptions())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("projectIds");
    }

    @Test
    void startBatchRun_returnsImmediatelyAfterDispatch() {
        UUID batchId = createReadyBatch(List.of(project("P1")), successOptions()).getId();

        long startedAt = System.nanoTime();
        var response = service.start(batchId);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(2_000L);
        assertThat(response.getStatus()).isEqualTo(BatchRunStatus.RUNNING);
        verify(batchRunWorker).runAsync(batchId);
    }

    @Test
    void startBatchRun_marksBatchRunningAndDispatchesWorker() {
        UUID batchId = createReadyBatch(List.of(project("P1")), successOptions()).getId();

        service.start(batchId);

        verify(batchRunLifecycleService).markBatchRunning(batchId);
        verify(batchRunWorker).runAsync(batchId);
    }

    @Test
    void startBatchRun_usesAuthenticatedStarterIfCreatedByMissing() {
        UUID batchId = createReadyBatch(List.of(project("P1")), successOptions()).getId();
        batches.get(batchId).setCreatedBy(null);

        var response = service.start(batchId);

        assertThat(response.getCreatedBy()).isEqualTo(currentUser.getId());
        assertThat(batches.get(batchId).getCreatedBy()).isEqualTo(currentUser.getId());
        verify(currentUserService).getCurrentUser();
    }

    @Test
    void startBatchRun_doesNotProcessItemSynchronouslyInRequestThread() {
        SourceProject p1 = project("P1");
        UUID batchId = createReadyBatch(List.of(p1), successOptions()).getId();

        service.start(batchId);

        assertThat(items.values().iterator().next().getStatus()).isEqualTo(BatchRunItemStatus.PENDING);
        verify(batchRunWorker).runAsync(batchId);
    }

    @Test
    void startBatchRun_rejectsAlreadyRunningBatch() {
        UUID batchId = createReadyBatch(List.of(project("P1")), successOptions()).getId();
        batches.get(batchId).setStatus(BatchRunStatus.RUNNING);

        assertThatThrownBy(() -> service.start(batchId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already RUNNING");
        verifyNoInteractions(batchRunWorker);
    }

    @Test
    void batchResponsePersistsBuildStrategy() {
        BatchRunOptionsRequest options = successOptions();
        options.setBuildStrategy(BuildStrategy.AUTO_WITH_FALLBACK);
        UUID batchId = createReadyBatch(List.of(project("P1")), options).getId();

        var response = service.getById(batchId);

        assertThat(response.getBuildStrategy()).isEqualTo(BuildStrategy.AUTO_WITH_FALLBACK);
    }

    private void wireRepositories() {
        when(batchRunRepository.save(any(BatchRun.class))).thenAnswer(inv -> {
            BatchRun batch = inv.getArgument(0);
            if (batch.getId() == null) batch.setId(UUID.randomUUID());
            if (batch.getCreatedAt() == null) batch.prePersist(); else batch.preUpdate();
            batches.put(batch.getId(), batch);
            return batch;
        });
        when(batchRunRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(batches.get(inv.getArgument(0))));
        when(batchRunLifecycleService.markBatchRunning(any())).thenAnswer(inv -> {
            BatchRun batch = batches.get(inv.getArgument(0));
            batch.setStatus(BatchRunStatus.RUNNING);
            batch.setStartedAt(java.time.LocalDateTime.now());
            return batch;
        });
        when(batchRunItemRepository.save(any(BatchRunItem.class))).thenAnswer(inv -> {
            BatchRunItem item = inv.getArgument(0);
            if (item.getId() == null) item.setId(UUID.randomUUID());
            if (item.getCreatedAt() == null) item.prePersist(); else item.preUpdate();
            items.put(item.getId(), item);
            return item;
        });
        when(batchRunItemRepository.findByBatchRun_IdOrderByCreatedAtAsc(any())).thenAnswer(inv -> items.values().stream()
                .filter(item -> item.getBatchRun().getId().equals(inv.getArgument(0)))
                .toList());
        when(sourceProjectRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(projects.get(inv.getArgument(0))));
        when(testCaseRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<UUID> ids = inv.getArgument(0);
            List<TestCase> found = new ArrayList<>();
            for (UUID id : ids) {
                TestCase testCase = testCases.get(id);
                if (testCase != null) {
                    found.add(testCase);
                }
            }
            return found;
        });
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
        }
        return batchRun;
    }

    private SourceProject project(String name) {
        SourceProject project = new SourceProject();
        project.setId(UUID.randomUUID());
        project.setProjectName(name);
        project.setDefaultTargetBaseUrl("http://default.test");
        projects.put(project.getId(), project);
        return project;
    }

    private TestCase testCase(SourceProject project, boolean active, boolean deleted, boolean requiresWrite) {
        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID());
        testCase.setSourceProject(project);
        testCase.setActiveFlag(active);
        testCase.setDeletedFlag(deleted);
        testCase.setRequiresWrite(requiresWrite);
        testCases.put(testCase.getId(), testCase);
        return testCase;
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
}
