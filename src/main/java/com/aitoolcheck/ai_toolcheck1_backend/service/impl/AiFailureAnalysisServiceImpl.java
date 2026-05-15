package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.constant.AiFailurePromptConstants;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.CreateAiJobLogRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseAiPayload;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureAnalysisResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiSkill;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiSkillRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiFailureAnalysisService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiFailureAnalysisServiceImpl implements AiFailureAnalysisService {

    private final ObjectMapper objectMapper;
    private final AiModelRouterService aiModelRouterService;
    private final AiSkillRepository aiSkillRepository;
    private final AiJobLogService aiJobLogService;

    @Override
    public String buildAnalyzeFailurePrompt(FailedTestCaseAiPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }

        try {
            String payloadJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            String promptTemplate = AiFailurePromptConstants.ANALYZE_FAILURE_SYSTEM_PROMPT;
            return promptTemplate.replace("{{FAILED_TEST_CASE_PAYLOAD}}", payloadJson);
        } catch (JsonProcessingException e) {
            log.error("[AiFailureAnalysis] Failed to serialize payload to JSON: {}", e.getMessage());
            throw new IllegalStateException("Could not serialize payload to JSON for AI Prompt", e);
        }
    }

    @Override
    public FailureAnalysisResponseDto analyzeFailure(FailedTestCaseAiPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }

        log.info("Starting AI failure analysis for testResultId={}, testCaseId={}", 
            payload.getTestResultId(), payload.getTestCaseId());

        AiSkill skill = aiSkillRepository.findBySkillCode("analyze_test_result")
                .orElseThrow(() -> new IllegalStateException("AI Skill analyze_test_result not found. Please check AiSkillDataSeeder."));

        CreateAiJobLogRequest createRequest = CreateAiJobLogRequest.builder()
                .projectId(payload.getProjectId())
                .apiEndpointId(payload.getEndpoint() != null ? payload.getEndpoint().getEndpointId() : null)
                .testResultId(payload.getTestResultId())
                .aiSkillId(skill.getId())
                .jobType(JobType.FAILURE_ANALYSIS)
                .executionStatus(ExecutionStatus.PENDING)
                .build();
        
        AiJobLogResponse jobLog = aiJobLogService.createPendingJob(createRequest);
        UUID jobId = jobLog.getId();

        try {
            aiJobLogService.markJobAsRunning(jobId);

            String prompt = buildAnalyzeFailurePrompt(payload);
            log.info("Router call started for jobId={}", jobId);

            String rawResponse = aiModelRouterService.routeAndExecute(prompt, jobId);
            
            log.info("Router call success for jobId={}", jobId);

            FailureAnalysisResponseDto responseDto = parseFailureAnalysisResponse(rawResponse);
            responseDto.setAiJobLogId(jobId);
            log.info("AI failure analysis completed for testResultId={}, failureType={}, confidence={}", 
                payload.getTestResultId(), responseDto.getFailureType(), responseDto.getConfidence());

            return responseDto;

        } catch (Exception e) {
            log.error("Router call or parse fail for jobId={}: {}", jobId, e.getMessage());
            aiJobLogService.markJobAsFailed(jobId, e.getMessage());
            throw new IllegalStateException("Failed to execute AI Failure Analysis", e);
        }
    }

    @Override
    public FailureAnalysisResponseDto parseFailureAnalysisResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            throw new AiJsonParseException(AiJsonParseException.ErrorType.INVALID_JSON_SYNTAX, "AI Response is empty");
        }

        String cleanedJson = cleanMarkdownFences(rawResponse);

        try {
            FailureAnalysisResponseDto responseDto = objectMapper.readValue(cleanedJson, FailureAnalysisResponseDto.class);
            responseDto.setRawAiResponse(rawResponse);
            return responseDto;
        } catch (JsonProcessingException e) {
            log.error("Failed to parse AI JSON response");
            throw new AiJsonParseException(AiJsonParseException.ErrorType.INVALID_JSON_SYNTAX, "Failed to parse AI JSON response: " + e.getMessage());
        }
    }

    private String cleanMarkdownFences(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        
        return cleaned.trim();
    }
}
