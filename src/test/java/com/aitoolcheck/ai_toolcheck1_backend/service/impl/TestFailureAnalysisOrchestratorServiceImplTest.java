package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseAiPayload;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureAnalysisResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.req.AnalyzeFailuresRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res.AnalyzeFailuresResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestFailureAnalysisRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiFailureAnalysisService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.FailedTestCaseCollectorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestFailureAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestFailureAnalysisOrchestratorServiceImplTest {

    @Mock private FailedTestCaseCollectorService failedTestCaseCollectorService;
    @Mock private AiFailureAnalysisService aiFailureAnalysisService;
    @Mock private TestFailureAnalysisService testFailureAnalysisService;
    @Mock private TestResultRepository testResultRepository;
    @Mock private TestFailureAnalysisRepository testFailureAnalysisRepository;
    @Mock private AiJobLogRepository aiJobLogRepository;
    @Mock private AiJobLogService aiJobLogService;

    private TestFailureAnalysisOrchestratorServiceImpl orchestrator;
    private UUID testRunId;
    private UUID testResultId;
    private UUID testCaseId;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        orchestrator = new TestFailureAnalysisOrchestratorServiceImpl(
                failedTestCaseCollectorService,
                aiFailureAnalysisService,
                testFailureAnalysisService,
                testResultRepository,
                testFailureAnalysisRepository,
                aiJobLogRepository,
                aiJobLogService);
        testRunId = UUID.randomUUID();
        testResultId = UUID.randomUUID();
        testCaseId = UUID.randomUUID();
        jobId = UUID.randomUUID();
    }

    @Test
    void failureAnalysis_providerReturnsValidJson_persistsAnalysisAndMarksSuccess() {
        FailedTestCaseAiPayload payload = FailedTestCaseAiPayload.builder()
                .testRunId(testRunId)
                .testResultId(testResultId)
                .testCaseId(testCaseId)
                .build();
        TestResult testResult = TestResult.builder()
                .id(testResultId)
                .resultStatus(ResultStatus.FAIL)
                .build();
        FailureAnalysisResponseDto dto = FailureAnalysisResponseDto.builder()
                .aiJobLogId(jobId)
                .summary("summary")
                .failureType("ASSERTION_MISMATCH")
                .confidence(0.9)
                .priority("MEDIUM")
                .build();
        AiJobLog jobLog = AiJobLog.builder()
                .id(jobId)
                .jobType(JobType.FAILURE_ANALYSIS)
                .executionStatus(ExecutionStatus.RUNNING)
                .build();
        TestFailureAnalysis saved = TestFailureAnalysis.builder()
                .id(UUID.randomUUID())
                .failureType("ASSERTION_MISMATCH")
                .summary("summary")
                .confidence(0.9)
                .priority("MEDIUM")
                .build();

        when(failedTestCaseCollectorService.collectUnanalyzedFailedTestCasesForAi(testRunId))
                .thenReturn(List.of(payload));
        when(testFailureAnalysisRepository.existsByTestResult_Id(testResultId)).thenReturn(false);
        when(testResultRepository.findById(testResultId)).thenReturn(Optional.of(testResult));
        when(aiFailureAnalysisService.analyzeFailure(payload)).thenReturn(dto);
        when(aiJobLogRepository.findById(jobId)).thenReturn(Optional.of(jobLog));
        when(testFailureAnalysisService.saveAnalysis(testResult, jobLog, dto)).thenReturn(saved);

        AnalyzeFailuresResponse response = orchestrator.analyzeFailuresByTestRun(testRunId, AnalyzeFailuresRequest.builder().build());

        assertThat(response.getTotalSuccess()).isEqualTo(1);
        assertThat(response.getTotalFailed()).isZero();
        InOrder order = inOrder(testFailureAnalysisService, aiJobLogService);
        order.verify(testFailureAnalysisService).saveAnalysis(testResult, jobLog, dto);
        order.verify(aiJobLogService).markJobAsSuccess(jobId, null, null, "router-selected");
    }

    @Test
    void failureAnalysis_doesNotMutateTestRunStatus() {
        FailedTestCaseAiPayload payload = FailedTestCaseAiPayload.builder()
                .testRunId(testRunId)
                .testResultId(testResultId)
                .testCaseId(testCaseId)
                .build();
        TestResult testResult = TestResult.builder()
                .id(testResultId)
                .resultStatus(ResultStatus.FAIL)
                .build();
        FailureAnalysisResponseDto dto = FailureAnalysisResponseDto.builder()
                .aiJobLogId(jobId)
                .summary("summary")
                .failureType("ASSERTION_MISMATCH")
                .confidence(0.9)
                .priority("MEDIUM")
                .build();
        TestFailureAnalysis saved = TestFailureAnalysis.builder()
                .id(UUID.randomUUID())
                .failureType("ASSERTION_MISMATCH")
                .summary("summary")
                .confidence(0.9)
                .priority("MEDIUM")
                .build();

        when(failedTestCaseCollectorService.collectUnanalyzedFailedTestCasesForAi(testRunId))
                .thenReturn(List.of(payload));
        when(testFailureAnalysisRepository.existsByTestResult_Id(testResultId)).thenReturn(false);
        when(testResultRepository.findById(testResultId)).thenReturn(Optional.of(testResult));
        when(aiFailureAnalysisService.analyzeFailure(payload)).thenReturn(dto);
        when(aiJobLogRepository.findById(jobId)).thenReturn(Optional.of(AiJobLog.builder().id(jobId).build()));
        when(testFailureAnalysisService.saveAnalysis(eq(testResult), any(), eq(dto))).thenReturn(saved);

        orchestrator.analyzeFailuresByTestRun(testRunId, AnalyzeFailuresRequest.builder().build());

        assertThat(testResult.getResultStatus()).isEqualTo(ResultStatus.FAIL);
        verify(testResultRepository, never()).save(any());
    }
}
