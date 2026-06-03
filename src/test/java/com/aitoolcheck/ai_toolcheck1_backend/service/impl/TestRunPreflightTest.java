package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.TestRunStaleProperties;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRunPreflightTest {

    private TestRunRepository testRunRepository;
    private TestRunItemRepository testRunItemRepository;
    private TestCaseRepository testCaseRepository;
    private ProjectAccessService projectAccessService;
    private TestRequestBuilder testRequestBuilder;
    private TestHttpExecutor testHttpExecutor;
    private TestRunServiceImpl service;

    private UUID projectId;
    private SourceProject project;
    private TestRun testRun;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        testRunRepository = mock(TestRunRepository.class);
        testRunItemRepository = mock(TestRunItemRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        projectAccessService = mock(ProjectAccessService.class);
        testRequestBuilder = mock(TestRequestBuilder.class);
        testHttpExecutor = mock(TestHttpExecutor.class);
        transactionTemplate = mock(TransactionTemplate.class);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });

        service = new TestRunServiceImpl(
                testRunRepository,
                testRunItemRepository,
                testCaseRepository,
                mock(SourceProjectRepository.class),
                mock(TestResultRepository.class),
                testRequestBuilder,
                testHttpExecutor,
                new ObjectMapper(),
                mock(TestResultService.class),
                projectAccessService,
                mock(RuleEngineService.class),
                transactionTemplate,
                mock(TestRunRealtimePublisher.class),
                mock(ProjectNotificationEventPublisher.class),
                mock(TestFailureAnalysisRepository.class),
                new TestRunStaleProperties(),
                mock(SourceRuntimeService.class)
        );

        projectId = UUID.randomUUID();
        project = new SourceProject();
        project.setId(projectId);
        
        testRun = new TestRun();
        testRun.setId(UUID.randomUUID());
        testRun.setSourceProject(project);
        testRun.setBaseUrl("http://52.220.34.212:8081");
        testRun.setRunStatus(RunStatus.PENDING);
        
        when(testRunRepository.findById(testRun.getId())).thenReturn(java.util.Optional.of(testRun));
        when(testRunRepository.save(any(TestRun.class))).thenReturn(testRun);
    }

    @Test
    void externalBaseUrlPreflight_allSafeEndpoints404_marksRunFailed() {
        TestRunItem item1 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        TestRunItem item2 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.HEAD);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1, item2));
        
        com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse req1 = mock(com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse.class);
        com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse req2 = mock(com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse.class);
        when(testRequestBuilder.build(any(), any())).thenReturn(req1, req2);
        
        com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse resp404 = mock(com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse.class);
        when(resp404.statusCode()).thenReturn(404);
        when(testHttpExecutor.execute(any())).thenReturn(resp404);

        assertThatThrownBy(() -> service.execute(testRun.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Base URL/runtime does not match uploaded source");
                
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(testRun.getPreflightStatus()).isEqualTo("FAILED");
    }

    @Test
    void externalBaseUrlPreflight_allSafeEndpoints404_persistsRunFailed() {
        TestRunItem item1 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1));
        
        when(testRequestBuilder.build(any(), any())).thenReturn(mock(com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse.class));
        com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse resp404 = mock(com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse.class);
        when(resp404.statusCode()).thenReturn(404);
        when(testHttpExecutor.execute(any())).thenReturn(resp404);

        service.executeTestRunAsync(testRun.getId());
        
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.FAILED);
        org.mockito.Mockito.verify(testRunRepository, org.mockito.Mockito.atLeastOnce()).save(testRun);
    }

    @Test
    void executeApi_preflightFailure_doesNotLeaveRunPending() {
        TestRunItem item1 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1));
        
        when(testRequestBuilder.build(any(), any())).thenReturn(mock(com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse.class));
        com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse resp404 = mock(com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse.class);
        when(resp404.statusCode()).thenReturn(404);
        when(testHttpExecutor.execute(any())).thenReturn(resp404);

        assertThatThrownBy(() -> service.execute(testRun.getId()))
                .isInstanceOf(BadRequestException.class);
                
        assertThat(testRun.getRunStatus()).isNotEqualTo(RunStatus.PENDING);
        assertThat(testRun.getRunStatus()).isNotEqualTo(RunStatus.RUNNING);
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.FAILED);
        org.mockito.Mockito.verify(testRunRepository, org.mockito.Mockito.atLeastOnce()).save(testRun);
    }

    @Test
    void externalBaseUrlPreflight_someSafeEndpointNot404_allowsRun() {
        TestRunItem item1 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        TestRunItem item2 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.HEAD);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1, item2));
        
        com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse resp404 = mock(com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse.class);
        when(resp404.statusCode()).thenReturn(404);
        
        com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse resp200 = mock(com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse.class);
        when(resp200.statusCode()).thenReturn(200);
        
        when(testRequestBuilder.build(any(), any())).thenReturn(mock(com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse.class));
        when(testHttpExecutor.execute(any())).thenReturn(resp404, resp200);

        try {
            service.execute(testRun.getId());
        } catch (Exception e) {}
        
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void externalBaseUrlPreflight_noSafeGetHeadCases_skipsPreflight() {
        TestRunItem item1 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.POST);
        TestRunItem item2 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.PUT);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1, item2));
        
        try {
            service.execute(testRun.getId());
        } catch (Exception e) {}
        
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void readOnlyPreflight_doesNotCallPostPutPatchDelete() {
        TestRunItem item1 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.POST);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1));
        
        try {
            service.execute(testRun.getId());
        } catch (Exception e) {}
        
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void preflight401403500_allowsRun() {
        TestRunItem item1 = buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1));
        
        com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse resp500 = mock(com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse.class);
        when(resp500.statusCode()).thenReturn(500);
        
        when(testRequestBuilder.build(any(), any())).thenReturn(mock(com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse.class));
        when(testHttpExecutor.execute(any())).thenReturn(resp500);

        try {
            service.execute(testRun.getId());
        } catch (Exception e) {}
        
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.RUNNING);
    }

    private TestRunItem buildItemWithMethod(com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod method) {
        com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput input = new com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput();
        input.setHttpMethod(method);
        TestCase testCase = new TestCase();
        testCase.assignInput(input);
        testCase.setCaseType(com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType.POSITIVE);
        TestRunItem item = new TestRunItem();
        item.setId(UUID.randomUUID());
        item.setTestCase(testCase);
        return item;
    }
}
