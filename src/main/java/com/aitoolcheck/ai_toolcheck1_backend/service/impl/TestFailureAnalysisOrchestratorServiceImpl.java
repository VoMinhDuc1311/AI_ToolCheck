package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseAiPayload;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureAnalysisResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.req.AnalyzeFailuresRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res.AnalyzeFailureItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res.AnalyzeFailuresResponse;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiFailureAnalysisService;
import com.aitoolcheck.ai_toolcheck1_backend.service.FailedTestCaseCollectorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestFailureAnalysisOrchestratorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestFailureAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestFailureAnalysisOrchestratorServiceImpl implements TestFailureAnalysisOrchestratorService {

    private final FailedTestCaseCollectorService failedTestCaseCollectorService;
    private final AiFailureAnalysisService aiFailureAnalysisService;
    private final TestFailureAnalysisService testFailureAnalysisService;
    private final TestResultRepository testResultRepository;
    private final AiJobLogRepository aiJobLogRepository;

    @Override
    public AnalyzeFailuresResponse analyzeFailuresByTestRun(UUID testRunId, AnalyzeFailuresRequest request) {
        if (testRunId == null) {
            throw new IllegalArgumentException("testRunId must not be null");
        }

        // Normalize request
        boolean reAnalyze = request != null && Boolean.TRUE.equals(request.getReAnalyze());
        boolean returnExistingWhenSkipped = request == null || request.getReturnExistingWhenSkipped() == null || request.getReturnExistingWhenSkipped();
        int maxItems = (request != null && request.getMaxItems() != null) ? request.getMaxItems() : 0;
        long delayMs = (request != null && request.getDelayMsBetweenCalls() != null && request.getDelayMsBetweenCalls() > 0) ? request.getDelayMsBetweenCalls() : 0;

        log.info("Start analyze failures batch for testRunId={}, reAnalyze={}, maxItems={}, delayMs={}", testRunId, reAnalyze, maxItems, delayMs);

        List<FailedTestCaseAiPayload> payloads = failedTestCaseCollectorService.collectFailedTestCasesForAi(testRunId);
        int totalFailedFound = payloads.size();
        log.info("Total failed payloads collected: {}", totalFailedFound);

        if (payloads.isEmpty()) {
            return AnalyzeFailuresResponse.builder()
                    .testRunId(testRunId)
                    .totalFailedFound(0)
                    .totalSubmitted(0)
                    .totalSuccess(0)
                    .totalFailed(0)
                    .totalSkipped(0)
                    .items(new ArrayList<>())
                    .message("No failed test cases found for analysis.")
                    .build();
        }

        if (maxItems > 0 && payloads.size() > maxItems) {
            payloads = payloads.subList(0, maxItems);
        }

        int totalSuccess = 0;
        int totalFailed = 0;
        int totalSkipped = 0;
        List<AnalyzeFailureItemResponse> itemResponses = new ArrayList<>();

        for (int i = 0; i < payloads.size(); i++) {
            FailedTestCaseAiPayload payload = payloads.get(i);
            UUID testResultId = payload.getTestResultId();
            UUID testCaseId = payload.getTestCaseId();

            log.info("Start item: testResultId={}, testCaseId={}", testResultId, testCaseId);

            AnalyzeFailureItemResponse itemResponse = AnalyzeFailureItemResponse.builder()
                    .testResultId(testResultId)
                    .testCaseId(testCaseId)
                    .build();

            if (testResultId == null) {
                itemResponse.setStatus("FAILED");
                itemResponse.setErrorMessage("testResultId is null in payload");
                itemResponses.add(itemResponse);
                totalFailed++;
                continue;
            }

            try {
                // Check if existing
                Optional<TestFailureAnalysis> existingAnalysisOpt = testFailureAnalysisService.findLatestByTestResultId(testResultId);
                
                if (existingAnalysisOpt.isPresent() && !reAnalyze) {
                    itemResponse.setStatus("SKIPPED");
                    log.info("Item skipped: testResultId={} already has analysis", testResultId);
                    totalSkipped++;
                    
                    if (returnExistingWhenSkipped) {
                        TestFailureAnalysis existing = existingAnalysisOpt.get();
                        itemResponse.setAnalysisId(existing.getId());
                        itemResponse.setAiJobLogId(existing.getAiJobLog() != null ? existing.getAiJobLog().getId() : null);
                        itemResponse.setFailureType(existing.getFailureType());
                        itemResponse.setSummary(existing.getSummary());
                        itemResponse.setConfidence(existing.getConfidence());
                        itemResponse.setPriority(existing.getPriority());
                    }
                    itemResponses.add(itemResponse);
                    continue;
                }

                if (i > 0 && delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Thread interrupted while sleeping between AI calls");
                        itemResponse.setStatus("FAILED");
                        itemResponse.setErrorMessage("Interrupted during delay");
                        itemResponses.add(itemResponse);
                        totalFailed++;
                        break; // stop batch safely
                    }
                }

                // Call AI
                FailureAnalysisResponseDto responseDto = aiFailureAnalysisService.analyzeFailure(payload);
                
                // Get TestResult entity
                TestResult testResult = testResultRepository.findById(testResultId).orElse(null);
                if (testResult == null) {
                    itemResponse.setStatus("FAILED");
                    itemResponse.setErrorMessage("TestResult entity not found");
                    itemResponses.add(itemResponse);
                    totalFailed++;
                    continue;
                }
                
                // Get AiJobLog entity if available
                AiJobLog aiJobLog = null;
                if (responseDto.getAiJobLogId() != null) {
                    aiJobLog = aiJobLogRepository.findById(responseDto.getAiJobLogId()).orElse(null);
                }

                // Save analysis
                TestFailureAnalysis savedAnalysis = testFailureAnalysisService.saveAnalysis(testResult, aiJobLog, responseDto);
                
                itemResponse.setStatus("SUCCESS");
                itemResponse.setAnalysisId(savedAnalysis.getId());
                itemResponse.setAiJobLogId(responseDto.getAiJobLogId());
                itemResponse.setFailureType(savedAnalysis.getFailureType());
                itemResponse.setSummary(savedAnalysis.getSummary());
                itemResponse.setConfidence(savedAnalysis.getConfidence());
                itemResponse.setPriority(savedAnalysis.getPriority());
                
                log.info("Item success: testResultId={}, analysisId={}, failureType={}, confidence={}", 
                        testResultId, savedAnalysis.getId(), savedAnalysis.getFailureType(), savedAnalysis.getConfidence());
                totalSuccess++;

            } catch (Exception e) {
                log.error("Item failed: testResultId={}, error class={}, message={}", testResultId, e.getClass().getSimpleName(), e.getMessage());
                itemResponse.setStatus("FAILED");
                itemResponse.setErrorMessage(e.getMessage());
                totalFailed++;
            }
            itemResponses.add(itemResponse);
        }

        log.info("Batch summary: success={}, failed={}, skipped={}", totalSuccess, totalFailed, totalSkipped);

        return AnalyzeFailuresResponse.builder()
                .testRunId(testRunId)
                .totalFailedFound(totalFailedFound)
                .totalSubmitted(payloads.size())
                .totalSuccess(totalSuccess)
                .totalFailed(totalFailed)
                .totalSkipped(totalSkipped)
                .items(itemResponses)
                .message("Failure analysis batch completed.")
                .build();
    }
}
