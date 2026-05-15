package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.FailureAnalysisResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestFailureAnalysisService {

    TestFailureAnalysis saveAnalysis(TestResult testResult, AiJobLog aiJobLog, FailureAnalysisResponseDto responseDto);

    Optional<TestFailureAnalysis> findLatestByTestResultId(UUID testResultId);

    boolean existsByTestResultId(UUID testResultId);

    List<TestFailureAnalysis> findHistoryByTestResultId(UUID testResultId);
}
