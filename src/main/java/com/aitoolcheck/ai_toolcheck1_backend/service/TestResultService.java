package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.HttpActualResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;

import java.util.List;

public interface TestResultService {
    RuleEngineResultDto evaluateAssertions(HttpActualResponseDto actualResponse, List<TestCaseAssertion> assertions);

    void saveTestResult(TestRunItem item, HttpActualResponseDto actualResponse, RuleEngineResultDto ruleResult);
}