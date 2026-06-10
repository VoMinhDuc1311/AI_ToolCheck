package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.constant.AiFailurePromptConstants;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.CreateAiJobLogRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedAssertionSnapshotDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseAiPayload;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureAnalysisResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureSuggestedFixDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiSkill;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiSkillRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiFailureAnalysisService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiFailureAnalysisServiceImpl implements AiFailureAnalysisService {

    private static final String SKILL_ANALYZE_TEST_RESULT = "analyze_test_result";
    private static final int REPAIR_RAW_PREVIEW_CHARS = 2000;
    private static final int LOG_RAW_PREVIEW_CHARS = 500;

    private final ObjectMapper objectMapper;
    private final AiModelRouterService aiModelRouterService;
    private final AiSkillRepository aiSkillRepository;
    private final AiJobLogService aiJobLogService;
    private final AiPayloadOptimizerService aiPayloadOptimizerService;
    private final AiOptimizationProperties aiOptimizationProperties;

    @Override
    public String buildAnalyzeFailurePrompt(FailedTestCaseAiPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }

        if (aiOptimizationProperties.isEnabled() && aiOptimizationProperties.isTruncateLargePayload()) {
            int maxChars = 5000; // safe limit for fields
            if (payload.getActual() != null && payload.getActual().getActualResponseJson() != null) {
                payload.getActual().setActualResponseJson(aiPayloadOptimizerService.safeJsonPreview(payload.getActual().getActualResponseJson(), maxChars));
            }
            if (payload.getRequest() != null && payload.getRequest().getRequestBodyJson() != null) {
                payload.getRequest().setRequestBodyJson(aiPayloadOptimizerService.safeJsonPreview(payload.getRequest().getRequestBodyJson(), maxChars));
            }
            if (payload.getRequest() != null && payload.getRequest().getHeadersJson() != null) {
                payload.getRequest().setHeadersJson(aiPayloadOptimizerService.safeJsonPreview(payload.getRequest().getHeadersJson(), maxChars));
            }
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

        log.info("[FailureAnalysis] testRunId={} testResultId={} testCaseId={}",
                payload.getTestRunId(), payload.getTestResultId(), payload.getTestCaseId());

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

            String rawResponse = aiModelRouterService.routeAndExecuteForSkillRaw(SKILL_ANALYZE_TEST_RESULT, prompt);
            log.info("[FailureAnalysis] provider rawResponseChars={}", rawResponse != null ? rawResponse.length() : 0);
            log.info("Router call success for jobId={}", jobId);

            FailureAnalysisResponseDto responseDto = parseOrRepairOrFallback(rawResponse, payload);
            responseDto.setAiJobLogId(jobId);
            log.info("AI failure analysis completed for testResultId={}, failureType={}, confidence={}", 
                payload.getTestResultId(), responseDto.getFailureType(), responseDto.getConfidence());

            return responseDto;

        } catch (Exception e) {
            try {
                log.warn("[FailureAnalysis][Fallback] creating deterministic failure analysis after providerFailure={}", rootMessage(e));
                FailureAnalysisResponseDto fallback = createDeterministicFallback(payload, null, rootMessage(e));
                fallback.setAiJobLogId(jobId);
                return fallback;
            } catch (Exception fallbackError) {
                log.error("Router/parse/fallback failed for jobId={}: {}", jobId, fallbackError.getMessage());
                aiJobLogService.markJobAsFailed(jobId, fallbackError.getMessage());
                throw new IllegalStateException("Failed to execute AI Failure Analysis", fallbackError);
            }
        }
    }

    private FailureAnalysisResponseDto parseOrRepairOrFallback(String rawResponse, FailedTestCaseAiPayload payload) {
        try {
            return parseFailureAnalysisResponse(rawResponse);
        } catch (AiJsonParseException parseError) {
            log.error("[FailureAnalysis][Parser][ERROR] invalidJson rootCause={} rawFirst500={}",
                    rootMessage(parseError), sanitizedPreview(rawResponse, LOG_RAW_PREVIEW_CHARS));
            log.info("[FailureAnalysis][Repair] retrying once with JSON repair prompt");
            try {
                String repairedRaw = aiModelRouterService.routeAndExecuteForSkillRaw(
                        SKILL_ANALYZE_TEST_RESULT,
                        buildRepairPrompt(rawResponse));
                log.info("[FailureAnalysis] provider rawResponseChars={}", repairedRaw != null ? repairedRaw.length() : 0);
                FailureAnalysisResponseDto repaired = parseFailureAnalysisResponse(repairedRaw);
                log.info("[FailureAnalysis][Repair] success");
                return repaired;
            } catch (Exception repairError) {
                log.error("[FailureAnalysis][Repair][ERROR] providerFailure={}", rootMessage(repairError));
                log.info("[FailureAnalysis][Fallback] creating deterministic failure analysis");
                return createDeterministicFallback(payload, rawResponse, rootMessage(repairError));
            }
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

    private String buildRepairPrompt(String rawResponse) {
        return """
The previous AI response was invalid JSON. Return valid JSON only.
No markdown. No prose. No code fences.

Return exactly this JSON shape:
{
  "summary": "...",
  "failureType": "ASSERTION_MISMATCH",
  "rootCause": "...",
  "expectedBehavior": "...",
  "actualBehavior": "...",
  "isLikelyBackendBug": false,
  "isLikelyTestCaseBug": true,
  "suggestedFixes": [
    {
      "targetLayer": "TestCase",
      "file": null,
      "method": null,
      "suggestion": "..."
    }
  ],
  "recommendedNextAction": "...",
  "confidence": 0.8,
  "priority": "MEDIUM"
}

Rules:
- Escape quotes correctly.
- Keep fields concise.
- Do not add extra fields.

Invalid response preview:
""" + sanitizedPreview(rawResponse, REPAIR_RAW_PREVIEW_CHARS);
    }

    private FailureAnalysisResponseDto createDeterministicFallback(
            FailedTestCaseAiPayload payload,
            String rawAiResponse,
            String fallbackReason) {
        String method = requestMethod(payload);
        String path = requestPath(payload);
        String assertions = expectedAssertions(payload);
        String actualStatus = actualStatus(payload);
        String errorMessage = actualError(payload);
        String bodyPreview = actualBodyPreview(payload);
        boolean unresolvedPathVariable = containsUnresolvedPathVariable(path);
        boolean missingInput = containsIgnoreCase(errorMessage, "No TestCaseInput")
                || containsIgnoreCase(bodyPreview, "No TestCaseInput");

        String rootCause;
        if (unresolvedPathVariable) {
            rootCause = "The failure is caused by an unresolved or invalid path variable. Request path used "
                    + path + ", which still contains braces and is treated as unresolved.";
        } else if (missingInput) {
            rootCause = "The request was not fully executable because the runner reported missing TestCaseInput evidence.";
        } else if (!"unknown".equals(actualStatus)) {
            rootCause = "The actual HTTP status " + actualStatus
                    + " did not satisfy the expected assertion: " + assertions + ".";
        } else {
            rootCause = "The actual execution result did not satisfy the expected assertion.";
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("method=" + method);
        evidence.add("requestPath=" + path);
        evidence.add("expectedAssertion=" + assertions);
        evidence.add("actualStatus=" + actualStatus);
        if (!isBlank(errorMessage)) {
            evidence.add("errorMessage=" + errorMessage);
        }
        if (!isBlank(bodyPreview)) {
            evidence.add("responseBody=" + bodyPreview);
        }
        if (!isBlank(fallbackReason)) {
            evidence.add("aiRecoveryReason=" + fallbackReason);
        }

        List<FailureSuggestedFixDto> suggestedFixes = new ArrayList<>();
        if (unresolvedPathVariable) {
            suggestedFixes.add(FailureSuggestedFixDto.builder()
                    .targetLayer("TestCase")
                    .file(null)
                    .method(null)
                    .suggestion("Replace unresolved path variables with concrete values. For path params, use /api/users/1 instead of /api/users/{id} or /api/users/{1}.")
                    .build());
            suggestedFixes.add(FailureSuggestedFixDto.builder()
                    .targetLayer("TestCase")
                    .file(null)
                    .method(null)
                    .suggestion("Verify the runtime contains the requested entity id, then re-run the testcase after updating the request path or path param input.")
                    .build());
        } else {
            suggestedFixes.add(FailureSuggestedFixDto.builder()
                    .targetLayer("TestCase")
                    .file(null)
                    .method(null)
                    .suggestion("Compare the expected assertion with the actual response and update the testcase input or backend data fixture before re-running.")
                    .build());
        }

        return FailureAnalysisResponseDto.builder()
                .summary("The test failed because the actual execution result did not satisfy the expected assertion.")
                .failureType(unresolvedPathVariable || missingInput ? "TEST_DATA_ERROR" : "ASSERTION_MISMATCH")
                .rootCause(rootCause + " Evidence: " + String.join("; ", evidence))
                .expectedBehavior("Expected assertion: " + assertions)
                .actualBehavior("Actual result: method=" + method + ", path=" + path
                        + ", status=" + actualStatus
                        + (isBlank(errorMessage) ? "" : ", error=" + errorMessage)
                        + (isBlank(bodyPreview) ? "" : ", body=" + bodyPreview))
                .isLikelyBackendBug(false)
                .isLikelyTestCaseBug(unresolvedPathVariable || missingInput)
                .suggestedFixes(suggestedFixes)
                .recommendedNextAction(unresolvedPathVariable
                        ? "Use a concrete path such as /api/users/1, verify that the user id exists, and re-run the testcase."
                        : "Inspect the actual response evidence, adjust the testcase or backend data, and re-run the testcase.")
                .confidence(unresolvedPathVariable || missingInput || !"unknown".equals(actualStatus) ? 0.9 : 0.6)
                .priority("MEDIUM")
                .rawAiResponse("DETERMINISTIC_FALLBACK"
                        + (isBlank(rawAiResponse) ? "" : "\nrawAiResponsePreview=" + sanitizedPreview(rawAiResponse, 1000)))
                .build();
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

    private String requestMethod(FailedTestCaseAiPayload payload) {
        if (payload.getRequest() != null && payload.getRequest().getHttpMethod() != null) {
            return payload.getRequest().getHttpMethod().name();
        }
        if (payload.getEndpoint() != null && payload.getEndpoint().getHttpMethod() != null) {
            return payload.getEndpoint().getHttpMethod().name();
        }
        return "unknown";
    }

    private String requestPath(FailedTestCaseAiPayload payload) {
        if (payload.getRequest() != null && !isBlank(payload.getRequest().getRequestPath())) {
            return payload.getRequest().getRequestPath();
        }
        if (payload.getEndpoint() != null && !isBlank(payload.getEndpoint().getEndpointPath())) {
            return payload.getEndpoint().getEndpointPath();
        }
        return "unknown";
    }

    private String expectedAssertions(FailedTestCaseAiPayload payload) {
        if (payload.getExpected() == null || payload.getExpected().getAssertions() == null
                || payload.getExpected().getAssertions().isEmpty()) {
            return "unknown";
        }
        List<String> parts = new ArrayList<>();
        for (FailedAssertionSnapshotDto assertion : payload.getExpected().getAssertions()) {
            if (assertion == null) {
                continue;
            }
            parts.add((assertion.getAssertionType() == null ? "ASSERTION" : assertion.getAssertionType().name())
                    + " " + (assertion.getOperator() == null ? "" : assertion.getOperator().name())
                    + " " + nullToUnknown(assertion.getExpectedValue())
                    + (isBlank(assertion.getTargetPath()) ? "" : " at " + assertion.getTargetPath()));
        }
        return parts.isEmpty() ? "unknown" : String.join("; ", parts);
    }

    private String actualStatus(FailedTestCaseAiPayload payload) {
        if (payload.getActual() == null || payload.getActual().getActualStatus() == null) {
            return "unknown";
        }
        return String.valueOf(payload.getActual().getActualStatus());
    }

    private String actualError(FailedTestCaseAiPayload payload) {
        if (payload.getActual() == null) {
            return null;
        }
        return sanitizedPreview(payload.getActual().getErrorMessage(), 300);
    }

    private String actualBodyPreview(FailedTestCaseAiPayload payload) {
        if (payload.getActual() == null) {
            return null;
        }
        String body = !isBlank(payload.getActual().getMaskedActualResponseJson())
                ? payload.getActual().getMaskedActualResponseJson()
                : payload.getActual().getActualResponseJson();
        return sanitizedPreview(body, 500);
    }

    private boolean containsUnresolvedPathVariable(String path) {
        return path != null && path.contains("{") && path.contains("}");
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String sanitizedPreview(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .trim();
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, maxChars) + "...";
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        Throwable root = throwable;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToUnknown(String value) {
        return isBlank(value) ? "unknown" : value;
    }
}
