package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureAnalysisResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestFailureAnalysisRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestFailureAnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestFailureAnalysisServiceImpl implements TestFailureAnalysisService {

    private final TestFailureAnalysisRepository testFailureAnalysisRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public TestFailureAnalysis saveAnalysis(TestResult testResult, AiJobLog aiJobLog, FailureAnalysisResponseDto responseDto) {
        if (testResult == null) {
            throw new IllegalArgumentException("testResult must not be null");
        }
        if (responseDto == null) {
            throw new IllegalArgumentException("failure analysis response must not be null");
        }

        log.info("Saving failure analysis for testResultId={}", testResult.getId());
        if (aiJobLog == null) {
            log.warn("aiJobLog is null when saving failure analysis for testResultId={}", testResult.getId());
        }

        String suggestedFixesJson = null;
        if (responseDto.getSuggestedFixes() != null && !responseDto.getSuggestedFixes().isEmpty()) {
            try {
                suggestedFixesJson = objectMapper.writeValueAsString(responseDto.getSuggestedFixes());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize suggested fixes for testResultId={}", testResult.getId(), e);
                throw new IllegalStateException("Failed to serialize suggested fixes", e);
            }
        }

        String modelName = aiJobLog != null ? aiJobLog.getModelName() : null;

        TestFailureAnalysis analysis = TestFailureAnalysis.builder()
                .testResult(testResult)
                .aiJobLog(aiJobLog)
                .modelName(modelName)
                .failureType(responseDto.getFailureType())
                .summary(responseDto.getSummary())
                .rootCause(responseDto.getRootCause())
                .expectedBehavior(responseDto.getExpectedBehavior())
                .actualBehavior(responseDto.getActualBehavior())
                .isLikelyBackendBug(responseDto.getIsLikelyBackendBug())
                .isLikelyTestCaseBug(responseDto.getIsLikelyTestCaseBug())
                .suggestedFixesJson(suggestedFixesJson)
                .recommendedNextAction(responseDto.getRecommendedNextAction())
                .confidence(responseDto.getConfidence())
                .priority(responseDto.getPriority())
                .rawAiResponse(responseDto.getRawAiResponse())
                .build();

        TestFailureAnalysis savedAnalysis = testFailureAnalysisRepository.save(analysis);
        log.info("Saved failure analysis id={} for testResultId={}", savedAnalysis.getId(), testResult.getId());

        return savedAnalysis;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TestFailureAnalysis> findLatestByTestResultId(UUID testResultId) {
        return testFailureAnalysisRepository.findFirstByTestResult_IdOrderByCreatedAtDesc(testResultId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTestResultId(UUID testResultId) {
        return testFailureAnalysisRepository.existsByTestResult_Id(testResultId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TestFailureAnalysis> findHistoryByTestResultId(UUID testResultId) {
        return testFailureAnalysisRepository.findByTestResult_IdOrderByCreatedAtDesc(testResultId);
    }
}
