package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;

import java.util.UUID;

public interface RuleEngineService {
    RuleEngineResultDto evaluate(UUID testResultId);
    RuleEngineResultDto evaluate(TestResult testResult);
}
