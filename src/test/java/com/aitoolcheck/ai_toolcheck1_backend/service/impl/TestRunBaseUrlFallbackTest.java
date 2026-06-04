package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.TestRunStaleProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
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
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
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
    private SourceRuntimeService sourceRuntimeService;
    private TestRunServiceImpl service;

    private UUID projectId;
    private SourceProject project;

    @BeforeEach
    void setUp() {
        testRunRepository = mock(TestRunRepository.class);
        testRunItemRepository = mock(TestRunItemRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        projectAccessService = mock(ProjectAccessService.class);
        sourceRuntimeService = mock(SourceRuntimeService.class);

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
                mock(TestFailureAnalysisRepository.class),
                new TestRunStaleProperties(),
                sourceRuntimeService
        );

        projectId = UUID.randomUUID();
        project = buildProject(projectId, null, null);

        when(sourceRuntimeService.resolveBaseUrlForTestRun(any(), any(), any()))
                .thenAnswer(invocation -> {
                    String reqUrl = invocation.getArgument(1);
                    String defUrl = invocation.getArgument(2);

                    if (reqUrl != null && !reqUrl.trim().isEmpty()) {
                        if (reqUrl.equals("not-a-url")) {
                            throw new BadRequestException("Invalid URL");
                        }
                        return reqUrl.trim();
                    }
                    if (defUrl != null && !defUrl.trim().isEmpty()) {
                        return defUrl.trim();
                    }
                    throw new BadRequestException("No runtime base URL provided. Provide External Base URL or start a Source Runtime.");
                });
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

    @Test
    void createTestRun_withoutRuntimeModeWithBaseUrl_defaultsExternal() {
        project.setDefaultTargetBaseUrl(null);
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
        stubSaveAndItems("http://52.220.34.212:8081");

        CreateTestRunRequest request = buildRequest("http://52.220.34.212:8081", null);

        var response = service.create(request);

        assertThat(response.getRuntimeMode()).isEqualTo(RuntimeMode.EXTERNAL_BASE_URL);
        assertThat(response.getTargetBaseUrlUsed()).isEqualTo("http://52.220.34.212:8081");
    }

    @Test
    void createTestRun_externalRuntime_usesProvidedBaseUrl() {
        project.setDefaultTargetBaseUrl("http://project-default.example.com");
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
        stubSaveAndItems("http://override.example.com");

        CreateTestRunRequest request = buildRequest("http://override.example.com", null);
        request.setRuntimeMode(RuntimeMode.EXTERNAL_BASE_URL);

        var response = service.create(request);

        assertThat(response.getRuntimeMode()).isEqualTo(RuntimeMode.EXTERNAL_BASE_URL);
        assertThat(response.getBaseUrl()).isEqualTo("http://override.example.com");
    }

    @Test
    void createTestRun_autoRuntime_resolvesBaseUrlSuccessfully() {
        // In Phase 1, autoRuntime is supported if baseUrl is provided (or resolved via other tiers)
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);
        stubSaveAndItems("http://override.example.com");

        CreateTestRunRequest request = buildRequest("http://override.example.com", null);
        request.setRuntimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE);

        var response = service.create(request);
        assertThat(response.getBaseUrl()).isEqualTo("http://override.example.com");
    }

    @Test
    void createTestRun_autoRuntime_noBaseUrl_throwsClearError() {
        when(projectAccessService.requireCanCreateTestRun(projectId)).thenReturn(project);

        CreateTestRunRequest request = buildRequest(null, null);
        request.setRuntimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No runtime base URL provided");
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
                .hasMessageContaining("No runtime base URL provided");
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
                .hasMessageContaining("No runtime base URL provided");
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

    @Test
    void getSkippedReason_reflectively_evaluatesMutationsAndFakeDataCorrectly() throws Exception {
        java.lang.reflect.Method method = TestRunServiceImpl.class.getDeclaredMethod(
                "getSkippedReason", TestRunItem.class, ExecutionMode.class);
        method.setAccessible(true);

        // Case 1: Mutating request (POST) in READ_ONLY mode
        com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput postInput = new com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput();
        postInput.setHttpMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.POST);
        postInput.setRequestPath("/legacy/customers");
        TestCase postCase = new TestCase();
        postCase.assignInput(postInput);
        postCase.setCaseType(com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType.POSITIVE);
        postCase.setRequiresWrite(true);
        TestRunItem postItem = new TestRunItem();
        postItem.setTestCase(postCase);

        String reason1 = (String) method.invoke(service, postItem, ExecutionMode.READ_ONLY);
        assertThat(reason1).contains("mutating request is not allowed");

        // Case 2: Mutating request (POST) in SAFE_WRITE mode -> allowed
        String reason2 = (String) method.invoke(service, postItem, ExecutionMode.SAFE_WRITE);
        assertThat(reason2).isNull();

        // Case 3: Positive GET request with fake path variable CUSTOMER_ABC_123 -> skipped
        com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput fakeGetInput = new com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput();
        fakeGetInput.setHttpMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        fakeGetInput.setRequestPath("/legacy/customers/CUSTOMER_ABC_123");
        TestCase fakeGetCase = new TestCase();
        fakeGetCase.assignInput(fakeGetInput);
        fakeGetCase.setCaseType(com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType.POSITIVE);
        fakeGetCase.setRequiresWrite(false);
        TestRunItem fakeGetItem = new TestRunItem();
        fakeGetItem.setTestCase(fakeGetCase);

        String reason3 = (String) method.invoke(service, fakeGetItem, ExecutionMode.SAFE_WRITE);
        assertThat(reason3).contains("positive path-variable test requires real test data");

        // Case 4: Negative GET request with UNKNOWN ID -> allowed
        com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput negativeGetInput = new com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput();
        negativeGetInput.setHttpMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        negativeGetInput.setRequestPath("/legacy/customers/UNKNOWN_CUSTOMER_ID");
        TestCase negativeGetCase = new TestCase();
        negativeGetCase.assignInput(negativeGetInput);
        negativeGetCase.setCaseType(com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType.NEGATIVE);
        negativeGetCase.setRequiresWrite(false);
        TestRunItem negativeGetItem = new TestRunItem();
        negativeGetItem.setTestCase(negativeGetCase);

        String reason4 = (String) method.invoke(service, negativeGetItem, ExecutionMode.READ_ONLY);
        assertThat(reason4).isNull();
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
                .runtimeMode(RuntimeMode.EXTERNAL_BASE_URL)
                .targetBaseUrlUsed(effectiveBaseUrl)
                .testRunItems(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(testRunRepository.save(any(TestRun.class))).thenReturn(savedRun);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(any())).thenReturn(new ArrayList<>());
    }
}
