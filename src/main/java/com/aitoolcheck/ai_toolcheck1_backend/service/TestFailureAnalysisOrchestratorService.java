package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.req.AnalyzeFailuresRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res.AnalyzeFailuresResponse;

import java.util.UUID;

public interface TestFailureAnalysisOrchestratorService {
    AnalyzeFailuresResponse analyzeFailuresByTestRun(UUID testRunId, AnalyzeFailuresRequest request);
}
