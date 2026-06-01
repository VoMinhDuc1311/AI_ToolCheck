package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRun;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestFailureAnalysisRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestRunItemRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestRunRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuleEngineService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunRealtimePublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestHttpExecutor;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestRequestBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TestRun baseUrl fallback logic in {@link TestRunServiceImpl}.
 *
 * <p>Tests the resolution priority:
 * <ol>
 *   <li>request.baseUrl (if provided) — used directly</li>
 *   <li>project.defaultTargetBaseUrl (fallback if request.baseUrl is blank)</li>
 *   <li>400 BadRequest if neither is set</li>
 * </ol>
 *
 * <p>Business rule enforced: repositoryUrl (GitHub source) must NEVER be used as baseUrl.
 */
class TestRunBaseUrlFallbackTest {

    private TestRunRepository testRunRepository;
    private TestRunItemRepository testRunItemRepository;
    private TestCaseRepository testCaseRepository;
    private ProjectAccessService projectAccessService;
    private TestRunServiceImpl service;

    private UUID projectId;
    private SourceProject project;

    @BeforeEach
    void setUp() {
        testRunRepository = mock(TestRunRepository.class);
        testRunItemRepository = mock(TestRunItemRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        projectAccessService = mock(ProjectAccessService.class);

        service = new TestRunServiceImpl(
                testRunRepository,
                testRunItemRepository,
                testCaseRepository,
                mock(SourceProjectRepository.class),
                mock(TestResultRepository.class),
                mock(TestRequestBuilder.class),
                mock(TestHttpExecutor.class),
                new ObjectMapper(),
                mock(TestResultService.class),
                projectAccessService,
                mock(RuleEngineService.class),
                mock(TransactionTemplate.class),
                mock(TestRunRealtimePublisher.class),
                mock(ProjectNotificationEventPublisher.class),
                mock(TestFailureAnalysisRepository.class)
        );

        projectId = UUID.randomUUID();
        project = buildProject(projectId, null, null);
    }

    // ── B1: request.baseUrl provided → use it ────────────────────────────────

    @Test
    void createTestRun_withRequestBaseUrl_usesRequestBaseUrl() {
        // project has no defaultTargetBaseUrl
        project.setDefaultTargetBaseUrl(null);
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
        stubSaveAndItems("http://52.220.34.212:8081");

        CreateTestRunRequest request = buildRequest("http://52.220.34.212:8081", null);

        var response = service.create(request);

        assertThat(response.getBaseUrl()).isEqualTo("http://52.220.34.212:8081");
    }

    // ── B2: no request.baseUrl → fallback to project.defaultTargetBaseUrl ────

    @Test
    void createTestRun_withoutRequestBaseUrl_usesProjectDefaultTargetBaseUrl() {
        project.setDefaultTargetBaseUrl("http://52.220.34.212:8081");
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
        stubSaveAndItems("http://52.220.34.212:8081");

        // request.baseUrl is null/blank
        CreateTestRunRequest request = buildRequest(null, null);

        var response = service.create(request);

        assertThat(response.getBaseUrl()).isEqualTo("http://52.220.34.212:8081");
    }

    // ── B3: neither set → 400 ────────────────────────────────────────────────

    @Test
    void createTestRun_withoutRequestBaseUrlAndNoProjectDefault_returnsBadRequest() {
        project.setDefaultTargetBaseUrl(null);
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);

        CreateTestRunRequest request = buildRequest(null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("baseUrl is required");
    }

    // ── B4: request.baseUrl overrides project default ─────────────────────────

    @Test
    void createTestRun_requestBaseUrl_overridesProjectDefaultTargetBaseUrl() {
        project.setDefaultTargetBaseUrl("http://project-default.example.com");
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
        stubSaveAndItems("http://override.example.com");

        CreateTestRunRequest request = buildRequest("http://override.example.com", null);

        var response = service.create(request);

        // Should use the request URL, not the project default
        assertThat(response.getBaseUrl()).isEqualTo("http://override.example.com");
    }

    // ── B5: repositoryUrl must NEVER be used as baseUrl ──────────────────────

    @Test
    void createTestRun_doesNotUseRepositoryUrlAsBaseUrl() {
        // Project has a GitHub repositoryUrl but NO defaultTargetBaseUrl
        project.setRepositoryUrl("https://github.com/spring-guides/gs-rest-service");
        project.setDefaultTargetBaseUrl(null);
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);

        CreateTestRunRequest request = buildRequest(null, null);

        // Must throw 400 — must NOT silently use repositoryUrl
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("baseUrl is required");
    }

    // ── B6: blank request.baseUrl treated as missing → fallback ───────────────

    @Test
    void createTestRun_blankRequestBaseUrl_fallsBackToProjectDefault() {
        project.setDefaultTargetBaseUrl("http://52.220.34.212:8081");
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
        stubSaveAndItems("http://52.220.34.212:8081");

        CreateTestRunRequest request = buildRequest("   ", null); // blank, not null

        var response = service.create(request);

        assertThat(response.getBaseUrl()).isEqualTo("http://52.220.34.212:8081");
    }

    // ── B7: invalid baseUrl in request → 400 ─────────────────────────────────

    @Test
    void createTestRun_withInvalidRequestBaseUrl_returnsBadRequest() {
        project.setDefaultTargetBaseUrl(null);
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);

        CreateTestRunRequest request = buildRequest("not-a-url", null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SourceProject buildProject(UUID id, String repositoryUrl, String defaultTargetBaseUrl) {
        SourceProject p = new SourceProject();
        p.setId(id);
        p.setProjectKey("test-proj");
        p.setProjectName("Test Project");
        p.setBackendType(BackendType.SPRING_BOOT);
        p.setStatus(ProjectStatus.NEW);
        p.setVisibility(ProjectVisibility.PRIVATE);
        p.setArchivedFlag(false);
        p.setDeletedFlag(false);
        p.setRepositoryUrl(repositoryUrl);
        p.setDefaultTargetBaseUrl(defaultTargetBaseUrl);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }

    private CreateTestRunRequest buildRequest(String baseUrl, List<UUID> testCaseIds) {
        return CreateTestRunRequest.builder()
                .projectId(projectId)
                .runName("Test Run")
                .baseUrl(baseUrl)
                .executionMode(ExecutionMode.READ_ONLY)
                .testCaseIds(testCaseIds != null ? testCaseIds : List.of())
                .includeAllActive(true)
                .build();
    }

    private void stubSaveAndItems(String effectiveBaseUrl) {
        // Build a minimal fake TestCase so resolveTestCases() doesn't throw "No active test cases"
        TestCase fakeTestCase = new TestCase();
        fakeTestCase.setId(UUID.randomUUID());
        fakeTestCase.setSourceProject(project);
        fakeTestCase.setActiveFlag(true);
        fakeTestCase.setDeletedFlag(false);
        fakeTestCase.setRequiresWrite(false);
        fakeTestCase.setCleanupRequired(false);
        fakeTestCase.setCreatedAt(LocalDateTime.now());
        fakeTestCase.setUpdatedAt(LocalDateTime.now());

        // includeAllActive=true uses this method:
        when(testCaseRepository
                .findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(projectId))
                .thenReturn(List.of(fakeTestCase));

        TestRun savedRun = TestRun.builder()
                .id(UUID.randomUUID())
                .sourceProject(project)
                .runCode("TR-TEST-001")
                .runName("Test Run")
                .baseUrl(effectiveBaseUrl)
                .runStatus(RunStatus.PENDING)
                .testRunItems(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(testRunRepository.save(any(TestRun.class))).thenReturn(savedRun);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(any())).thenReturn(new ArrayList<>());
    }
}
