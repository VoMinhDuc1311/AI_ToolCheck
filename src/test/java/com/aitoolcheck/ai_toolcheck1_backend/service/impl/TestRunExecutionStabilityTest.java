package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.TestRunStaleProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestHttpExecutor;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestRequestBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TestRunExecutionStabilityTest {

    private TestRunRepository testRunRepository;
    private TestRunItemRepository testRunItemRepository;
    private TestCaseRepository testCaseRepository;
    private TestResultRepository testResultRepository;
    private TestRequestBuilder testRequestBuilder;
    private TestHttpExecutor testHttpExecutor;
    private TestResultService testResultService;
    private RuleEngineService ruleEngineService;
    private TestRunRealtimePublisher testRunRealtimePublisher;
    private TransactionTemplate transactionTemplate;

    private TestRunServiceImpl service;

    private UUID projectId;
    private SourceProject project;
    private TestRun testRun;

    @BeforeEach
    void setUp() {
        testRunRepository = mock(TestRunRepository.class);
        testRunItemRepository = mock(TestRunItemRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        testResultRepository = mock(TestResultRepository.class);
        testRequestBuilder = mock(TestRequestBuilder.class);
        testHttpExecutor = mock(TestHttpExecutor.class);
        testResultService = mock(TestResultService.class);
        ruleEngineService = mock(RuleEngineService.class);
        testRunRealtimePublisher = mock(TestRunRealtimePublisher.class);
        transactionTemplate = mock(TransactionTemplate.class);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });

        // Stub executeWithoutResult to invoke the Consumer lambda synchronously
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new TestRunServiceImpl(
                testRunRepository,
                testRunItemRepository,
                testCaseRepository,
                mock(TestCaseAssertionRepository.class),
                mock(SourceProjectRepository.class),
                testResultRepository,
                mock(ApiEndpointRepository.class),
                testRequestBuilder,
                testHttpExecutor,
                new ObjectMapper(),
                testResultService,
                mock(ProjectAccessService.class),
                ruleEngineService,
                transactionTemplate,
                testRunRealtimePublisher,
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
        testRun.setBaseUrl("http://localhost:8080");
        testRun.setRunStatus(RunStatus.PENDING);

        when(testRunRepository.findById(testRun.getId())).thenReturn(Optional.of(testRun));
        when(testRunRepository.findByIdWithProjectGraph(testRun.getId())).thenReturn(Optional.of(testRun));
        when(testRunRepository.save(any(TestRun.class))).thenReturn(testRun);
    }

    @Test
    void executeTestRun_doesNotDereferenceDetachedTestCaseProxy() {
        TestRunItem graphItem = buildItem("/users", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        TestRunItem detachedProxyItem = mock(TestRunItem.class);
        when(detachedProxyItem.getId()).thenReturn(graphItem.getId());
        when(detachedProxyItem.getTestCase()).thenThrow(new org.hibernate.LazyInitializationException("no session"));

        when(testRunItemRepository.findByTestRunIdWithExecutionGraph(testRun.getId())).thenReturn(List.of(graphItem));
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(detachedProxyItem));
        when(testRunItemRepository.findByIdWithExecutionGraph(graphItem.getId())).thenReturn(Optional.of(graphItem));
        when(testRunItemRepository.findById(graphItem.getId())).thenReturn(Optional.of(graphItem));

        PreparedHttpRequestResponse prepared = mock(PreparedHttpRequestResponse.class);
        when(testRequestBuilder.build(any(), any())).thenReturn(prepared);

        ExecutedHttpResponse httpResp = mock(ExecutedHttpResponse.class);
        when(httpResp.statusCode()).thenReturn(200);
        when(testHttpExecutor.execute(prepared)).thenReturn(httpResp);

        TestResult result = new TestResult();
        result.setId(UUID.randomUUID());
        result.setTestRunItem(graphItem);
        result.setActualStatus(200);
        when(testResultService.saveRawTestResult(eq(graphItem), any())).thenReturn(result);
        when(ruleEngineService.evaluate(result.getId())).thenReturn(
                com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto.builder()
                        .testResultId(result.getId())
                        .finalStatus(ResultStatus.PASS)
                        .build());

        service.execute(testRun.getId());

        assertThat(graphItem.getItemStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(detachedProxyItem, never()).getTestCase();
    }

    @Test
    void executeTestRun_buildsExecutionPlanInsideTransaction() {
        TestRunItem item = buildItem("/users", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        when(testRunItemRepository.findByTestRunIdWithExecutionGraph(testRun.getId())).thenReturn(List.of(item));
        when(testRunItemRepository.findByIdWithExecutionGraph(item.getId())).thenReturn(Optional.of(item));
        when(testRunItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        PreparedHttpRequestResponse prepared = mock(PreparedHttpRequestResponse.class);
        when(testRequestBuilder.build(any(), any())).thenReturn(prepared);

        ExecutedHttpResponse httpResp = mock(ExecutedHttpResponse.class);
        when(httpResp.statusCode()).thenReturn(200);
        when(testHttpExecutor.execute(prepared)).thenReturn(httpResp);

        TestResult result = new TestResult();
        result.setId(UUID.randomUUID());
        result.setTestRunItem(item);
        when(testResultService.saveRawTestResult(eq(item), any())).thenReturn(result);
        when(ruleEngineService.evaluate(result.getId())).thenReturn(
                com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto.builder()
                        .finalStatus(ResultStatus.PASS)
                        .build());

        service.execute(testRun.getId());

        verify(transactionTemplate, atLeastOnce()).execute(any());
        verify(testRequestBuilder, atLeastOnce()).build(eq("http://localhost:8080"), any(TestCaseInput.class));
        verify(testHttpExecutor, atLeastOnce()).execute(prepared);
    }

    @Test
    void prepareTestRun_doesNotDereferenceDetachedTestCaseProxy() {
        TestRunItem graphItem = buildItem("/users", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        TestRunItem detachedProxyItem = mock(TestRunItem.class);
        when(detachedProxyItem.getId()).thenReturn(graphItem.getId());
        when(detachedProxyItem.getTestCase()).thenThrow(new org.hibernate.LazyInitializationException("no session"));

        when(testRunItemRepository.findByTestRunIdWithExecutionGraph(testRun.getId())).thenReturn(List.of(graphItem));
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(detachedProxyItem));
        when(testRequestBuilder.build(any(), any())).thenReturn(mock(PreparedHttpRequestResponse.class));

        var response = service.prepare(testRun.getId());

        assertThat(response.getItems()).hasSize(1);
        verify(detachedProxyItem, never()).getTestCase();
    }

    @Test
    void execute_withUnresolvedPathPlaceholder_guardsRequestAndMarksFailed() {
        TestRunItem item = buildItem("/users/{id}", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.POST);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item));
        when(testRunItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        TestResult testResult = new TestResult();
        testResult.setId(UUID.randomUUID());
        testResult.setTestRunItem(item);
        when(testResultService.saveRawTestResult(eq(item), any())).thenReturn(testResult);

        service.execute(testRun.getId());

        assertThat(item.getItemStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.FAILED);
        verify(testHttpExecutor, never()).execute(any());
    }

    @Test
    void execute_with404TargetResponse_isAssertionFailNotSystemError() {
        TestRunItem item = buildItem("/users", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item));
        when(testRunItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        PreparedHttpRequestResponse prepared = mock(PreparedHttpRequestResponse.class);
        when(testRequestBuilder.build(any(), any())).thenReturn(prepared);

        // Preflight check needs to NOT return 404 (return 200 first), then actual call returns 404
        ExecutedHttpResponse preflightResp = mock(ExecutedHttpResponse.class);
        when(preflightResp.statusCode()).thenReturn(200);

        ExecutedHttpResponse httpResp = mock(ExecutedHttpResponse.class);
        when(httpResp.statusCode()).thenReturn(404);

        when(testHttpExecutor.execute(prepared)).thenReturn(preflightResp, httpResp);

        TestResult testResult = new TestResult();
        testResult.setId(UUID.randomUUID());
        testResult.setTestRunItem(item);
        testResult.setActualStatus(404);
        when(testResultService.saveRawTestResult(eq(item), any())).thenReturn(testResult);

        // RuleEngine evaluates assertion as FAIL
        com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto ruleResult =
                com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto.builder()
                        .testResultId(testResult.getId())
                        .finalStatus(ResultStatus.FAIL)
                        .summaryMessage("Expected 200 but was 404")
                        .build();
        when(ruleEngineService.evaluate(testResult.getId())).thenReturn(ruleResult);

        service.execute(testRun.getId());

        assertThat(item.getItemStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void execute_withTimeoutOrConnectionRefused_isItemErrorAndRunNotHanging() {
        TestRunItem item = buildItem("/users", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item));
        when(testRunItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        PreparedHttpRequestResponse prepared = mock(PreparedHttpRequestResponse.class);
        when(testRequestBuilder.build(any(), any())).thenReturn(prepared);

        // Preflight returns 200, actual call returns 0 with error message
        ExecutedHttpResponse preflightResp = mock(ExecutedHttpResponse.class);
        when(preflightResp.statusCode()).thenReturn(200);

        ExecutedHttpResponse httpResp = mock(ExecutedHttpResponse.class);
        when(httpResp.statusCode()).thenReturn(0);
        when(httpResp.errorMessage()).thenReturn("Connection timed out");

        when(testHttpExecutor.execute(prepared)).thenReturn(preflightResp, httpResp);

        TestResult testResult = new TestResult();
        testResult.setId(UUID.randomUUID());
        testResult.setTestRunItem(item);
        testResult.setActualStatus(0);
        testResult.setErrorMessage("Connection timed out");
        when(testResultService.saveRawTestResult(eq(item), any())).thenReturn(testResult);

        // Network error causes RuleEngine to build NetworkErrorResult (ERROR status)
        com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto ruleResult =
                com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto.builder()
                        .testResultId(testResult.getId())
                        .finalStatus(ResultStatus.ERROR)
                        .summaryMessage("Connection timed out")
                        .build();
        when(ruleEngineService.evaluate(testResult.getId())).thenReturn(ruleResult);

        service.execute(testRun.getId());

        assertThat(item.getItemStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void execute_withMixedPassAndFailItems_aggregatesRunStatusToFailed() {
        TestRunItem item1 = buildItem("/users", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        TestRunItem item2 = buildItem("/roles", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1, item2));
        when(testRunItemRepository.findById(item1.getId())).thenReturn(Optional.of(item1));
        when(testRunItemRepository.findById(item2.getId())).thenReturn(Optional.of(item2));

        PreparedHttpRequestResponse prepared1 = mock(PreparedHttpRequestResponse.class);
        PreparedHttpRequestResponse prepared2 = mock(PreparedHttpRequestResponse.class);
        when(testRequestBuilder.build(eq("http://localhost:8080"), eq(item1.getTestCase().getTestCaseInput()))).thenReturn(prepared1);
        when(testRequestBuilder.build(eq("http://localhost:8080"), eq(item2.getTestCase().getTestCaseInput()))).thenReturn(prepared2);

        // Preflight calls will hit the same mocks.
        // For item1 preflight: returns 200 (pass preflight for whole run).
        // Then actual execute for item1: returns 200.
        ExecutedHttpResponse httpResp1 = mock(ExecutedHttpResponse.class);
        when(httpResp1.statusCode()).thenReturn(200);

        // For item2 execute: returns 404.
        ExecutedHttpResponse httpResp2 = mock(ExecutedHttpResponse.class);
        when(httpResp2.statusCode()).thenReturn(404);

        when(testHttpExecutor.execute(prepared1)).thenReturn(httpResp1);
        when(testHttpExecutor.execute(prepared2)).thenReturn(httpResp2);

        TestResult result1 = new TestResult();
        result1.setId(UUID.randomUUID());
        result1.setTestRunItem(item1);
        result1.setActualStatus(200);

        TestResult result2 = new TestResult();
        result2.setId(UUID.randomUUID());
        result2.setTestRunItem(item2);
        result2.setActualStatus(404);

        when(testResultService.saveRawTestResult(eq(item1), any())).thenReturn(result1);
        when(testResultService.saveRawTestResult(eq(item2), any())).thenReturn(result2);

        // RuleEngine evaluation stubbing
        com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto ruleResult1 =
                com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto.builder()
                        .testResultId(result1.getId())
                        .finalStatus(ResultStatus.PASS)
                        .build();
        com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto ruleResult2 =
                com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto.builder()
                        .testResultId(result2.getId())
                        .finalStatus(ResultStatus.FAIL)
                        .build();

        when(ruleEngineService.evaluate(result1.getId())).thenReturn(ruleResult1);
        when(ruleEngineService.evaluate(result2.getId())).thenReturn(ruleResult2);

        service.execute(testRun.getId());

        assertThat(item1.getItemStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(item2.getItemStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void execute_withAllPassItems_aggregatesRunStatusToCompleted() {
        TestRunItem item1 = buildItem("/users", com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET);
        when(testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(testRun.getId())).thenReturn(List.of(item1));
        when(testRunItemRepository.findById(item1.getId())).thenReturn(Optional.of(item1));

        PreparedHttpRequestResponse prepared = mock(PreparedHttpRequestResponse.class);
        when(testRequestBuilder.build(any(), any())).thenReturn(prepared);

        ExecutedHttpResponse httpResp = mock(ExecutedHttpResponse.class);
        when(httpResp.statusCode()).thenReturn(200);
        when(testHttpExecutor.execute(prepared)).thenReturn(httpResp);

        TestResult result = new TestResult();
        result.setId(UUID.randomUUID());
        result.setTestRunItem(item1);
        result.setActualStatus(200);
        when(testResultService.saveRawTestResult(eq(item1), any())).thenReturn(result);

        com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto ruleResult =
                com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto.builder()
                        .testResultId(result.getId())
                        .finalStatus(ResultStatus.PASS)
                        .build();
        when(ruleEngineService.evaluate(result.getId())).thenReturn(ruleResult);

        service.execute(testRun.getId());

        assertThat(item1.getItemStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(testRun.getRunStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    private TestRunItem buildItem(String path, com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod method) {
        TestCaseInput input = new TestCaseInput();
        input.setHttpMethod(method);
        input.setRequestPath(path);

        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID());
        testCase.assignInput(input);
        testCase.setCaseType(com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType.POSITIVE);

        TestRunItem item = new TestRunItem();
        item.setId(UUID.randomUUID());
        item.setTestCase(testCase);
        item.setItemStatus(ExecutionStatus.PENDING);
        item.setTestRun(testRun);
        return item;
    }
}
