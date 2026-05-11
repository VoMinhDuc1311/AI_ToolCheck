package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.ExecuteTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunResponse;

import java.util.List;
import java.util.UUID;

public interface TestRunService {

    TestRunDetailResponse create(CreateTestRunRequest request);

    TestRunDetailResponse createTestRun(ExecuteTestRunRequest request);

    TestRunDetailResponse getById(UUID id);

    List<TestRunResponse> getByProjectId(UUID projectId);

    TestRunDetailResponse prepare(UUID id);

    void executeTestRunAsync(UUID id);
}