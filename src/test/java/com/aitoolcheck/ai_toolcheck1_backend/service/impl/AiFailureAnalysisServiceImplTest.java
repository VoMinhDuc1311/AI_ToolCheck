package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedAssertionSnapshotDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedEndpointSnapshotDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseActualDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseAiPayload;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseExpectedDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseRequestSnapshotDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureAnalysisResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiSkill;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiSkillRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiFailureAnalysisServiceImplTest {

    @Mock private AiModelRouterService aiModelRouterService;
    @Mock private AiSkillRepository aiSkillRepository;
    @Mock private AiJobLogService aiJobLogService;
    @Mock private AiPayloadOptimizerService aiPayloadOptimizerService;

    private AiFailureAnalysisServiceImpl service;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        AiOptimizationProperties properties = new AiOptimizationProperties();
        properties.setEnabled(false);
        service = new AiFailureAnalysisServiceImpl(
                new ObjectMapper(),
                aiModelRouterService,
                aiSkillRepository,
                aiJobLogService,
                aiPayloadOptimizerService,
                properties);

        jobId = UUID.randomUUID();
        when(aiSkillRepository.findBySkillCode("analyze_test_result"))
                .thenReturn(Optional.of(AiSkill.builder().id(UUID.randomUUID()).skillCode("analyze_test_result").build()));
        when(aiJobLogService.createPendingJob(any()))
                .thenReturn(AiJobLogResponse.builder()
                        .id(jobId)
                        .jobType(JobType.FAILURE_ANALYSIS)
                        .executionStatus(ExecutionStatus.PENDING)
                        .build());
    }

    @Test
    void failureAnalysis_providerReturnsValidJson_persistsAnalysisAndMarksSuccess() {
        when(aiModelRouterService.routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString()))
                .thenReturn(validJson("valid summary"));

        FailureAnalysisResponseDto result = service.analyzeFailure(payloadWithUnresolvedPath());

        assertThat(result.getSummary()).isEqualTo("valid summary");
        assertThat(result.getAiJobLogId()).isEqualTo(jobId);
        verify(aiJobLogService).markJobAsRunning(jobId);
    }

    @Test
    void failureAnalysis_providerReturnsInvalidJson_repairSucceeds_marksSuccess() {
        when(aiModelRouterService.routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString()))
                .thenReturn("{\"summary\":\"truncated")
                .thenReturn(validJson("repaired summary"));

        FailureAnalysisResponseDto result = service.analyzeFailure(payloadWithUnresolvedPath());

        assertThat(result.getSummary()).isEqualTo("repaired summary");
        assertThat(result.getFailureType()).isEqualTo("ASSERTION_MISMATCH");
        verify(aiModelRouterService, org.mockito.Mockito.times(2))
                .routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString());
    }

    @Test
    void failureAnalysis_providerReturnsInvalidJson_repairInvalid_createsDeterministicFallback() {
        when(aiModelRouterService.routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString()))
                .thenReturn("{\"summary\":\"truncated")
                .thenReturn("{\"summary\":\"still bad");

        FailureAnalysisResponseDto result = service.analyzeFailure(payloadWithUnresolvedPath());

        assertThat(result.getFailureType()).isEqualTo("TEST_DATA_ERROR");
        assertThat(result.getRootCause()).contains("/api/users/{1}", "unresolved or invalid path variable");
        assertThat(result.getRecommendedNextAction()).contains("/api/users/1");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void failureAnalysis_providerReturnsInvalidJson_repairProviderTimeout_createsDeterministicFallback() {
        when(aiModelRouterService.routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString()))
                .thenReturn("{\"summary\":\"truncated")
                .thenThrow(AiProviderFailureException.timeout("Gemini", "gemini-2.5-flash", 60, 100, null));

        FailureAnalysisResponseDto result = service.analyzeFailure(payloadWithUnresolvedPath());

        assertThat(result.getRootCause()).contains("/api/users/{1}");
        assertThat(result.getRawAiResponse())
                .startsWith("DETERMINISTIC_FALLBACK")
                .contains("{\"summary\":\"truncated");
    }

    @Test
    void failureAnalysis_providerReturnsInvalidJson_repairRateLimited_createsDeterministicFallback() {
        when(aiModelRouterService.routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString()))
                .thenReturn("{\"summary\":\"truncated")
                .thenThrow(AiProviderFailureException.rateLimited("Gemini", "gemini-2.5-flash", 34, null));

        FailureAnalysisResponseDto result = service.analyzeFailure(payloadWithUnresolvedPath());

        assertThat(result.getFailureType()).isEqualTo("TEST_DATA_ERROR");
        assertThat(result.getSuggestedFixes()).extracting("suggestion")
                .anySatisfy(suggestion -> assertThat((String) suggestion).contains("concrete values"));
    }

    @Test
    void failureAnalysis_jobIsNotMarkedSuccessBeforeParseSucceeds() {
        when(aiModelRouterService.routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString()))
                .thenReturn(validJson("valid summary"));

        service.analyzeFailure(payloadWithUnresolvedPath());

        verify(aiJobLogService, never()).markJobAsSuccess(eq(jobId), any(), any());
        verify(aiJobLogService, never()).markJobAsSuccess(eq(jobId), any(), any(), anyString());
        verify(aiJobLogService, never()).markJobAsSuccess(eq(jobId), any(), any(), anyString(), anyString());
    }

    @Test
    void failureAnalysis_unresolvedPathVariableEvidence_isIncludedInFallback() {
        when(aiModelRouterService.routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString()))
                .thenReturn("{\"summary\":\"truncated")
                .thenReturn("{\"summary\":\"still bad");

        FailureAnalysisResponseDto result = service.analyzeFailure(payloadWithUnresolvedPath());

        assertThat(result.getRootCause())
                .contains("requestPath=/api/users/{1}")
                .contains("No TestCaseInput")
                .contains("actualStatus=404")
                .contains("STATUS_CODE EQUALS 200");
        assertThat(result.getActualBehavior()).contains("/api/users/{1}", "404");
    }

    @Test
    void failureAnalysis_ollamaTimeoutThenGeminiMalformed_doesNotFailWhenFallbackAllowed() {
        when(aiModelRouterService.routeAndExecuteForSkillRaw(eq("analyze_test_result"), anyString()))
                .thenReturn("{\"summary\":\"gemini malformed")
                .thenThrow(AiProviderFailureException.allProvidersFailed("Ollama timed out; Gemini malformed", null));

        FailureAnalysisResponseDto result = service.analyzeFailure(payloadWithUnresolvedPath());

        assertThat(result.getSummary()).contains("actual execution result");
        assertThat(result.getRootCause()).contains("/api/users/{1}");
    }

    private FailedTestCaseAiPayload payloadWithUnresolvedPath() {
        return FailedTestCaseAiPayload.builder()
                .projectId(UUID.randomUUID())
                .projectName("Demo")
                .testRunId(UUID.randomUUID())
                .testRunItemId(UUID.randomUUID())
                .testResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("Get user by id")
                .endpoint(FailedEndpointSnapshotDto.builder()
                        .endpointId(UUID.randomUUID())
                        .httpMethod(HttpMethod.GET)
                        .endpointPath("/api/users/{id}")
                        .build())
                .request(FailedTestCaseRequestSnapshotDto.builder()
                        .httpMethod(HttpMethod.GET)
                        .requestPath("/api/users/{1}")
                        .fullUrl("http://localhost:8080/api/users/{1}")
                        .build())
                .expected(FailedTestCaseExpectedDto.builder()
                        .assertions(java.util.List.of(FailedAssertionSnapshotDto.builder()
                                .assertionType(AssertionType.STATUS_CODE)
                                .operator(ComparisonOperator.EQUALS)
                                .expectedValue("200")
                                .build()))
                        .build())
                .actual(FailedTestCaseActualDto.builder()
                        .actualStatus(404)
                        .resultStatus(ResultStatus.FAIL)
                        .errorMessage("Unresolved path variable in requestPath='/api/users/{1}'. No TestCaseInput for item.")
                        .actualResponseJson("{\"error\":\"not found\"}")
                        .build())
                .build();
    }

    private String validJson(String summary) {
        return """
                {
                  "summary": "%s",
                  "failureType": "ASSERTION_MISMATCH",
                  "rootCause": "Actual status did not match expected status.",
                  "expectedBehavior": "Expected HTTP 200.",
                  "actualBehavior": "Actual HTTP 404.",
                  "isLikelyBackendBug": false,
                  "isLikelyTestCaseBug": true,
                  "suggestedFixes": [
                    {
                      "targetLayer": "TestCase",
                      "file": null,
                      "method": null,
                      "suggestion": "Use a concrete user id."
                    }
                  ],
                  "recommendedNextAction": "Update path params and re-run.",
                  "confidence": 0.9,
                  "priority": "MEDIUM"
                }
                """.formatted(summary);
    }
}
