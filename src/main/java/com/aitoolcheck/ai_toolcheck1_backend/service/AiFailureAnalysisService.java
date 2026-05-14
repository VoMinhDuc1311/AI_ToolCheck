package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseAiPayload;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureAnalysisResponseDto;

public interface AiFailureAnalysisService {

    /**
     * Builds the complete prompt string using the AI_FAILURE_SYSTEM_PROMPT
     * and the serialized FailedTestCaseAiPayload.
     *
     * @param payload The collected and masked test failure payload
     * @return The final prompt string ready to be sent to the LLM
     */
    String buildAnalyzeFailurePrompt(FailedTestCaseAiPayload payload);

    /**
     * Analyzes the failed test case by routing the prompt to the appropriate AI model.
     * Note: In Day 3, this is a skeleton and will throw UnsupportedOperationException.
     * Full implementation using AiModelRouterService will be done in Day 4.
     *
     * @param payload The payload to analyze
     * @return The parsed failure analysis response from the AI
     */
    FailureAnalysisResponseDto analyzeFailure(FailedTestCaseAiPayload payload);

    /**
     * Parses the raw JSON string returned by the LLM into the Response DTO.
     * Cleans up markdown fences if necessary.
     *
     * @param rawResponse The raw string returned by the AI
     * @return The parsed DTO
     */
    FailureAnalysisResponseDto parseFailureAnalysisResponse(String rawResponse);

}
