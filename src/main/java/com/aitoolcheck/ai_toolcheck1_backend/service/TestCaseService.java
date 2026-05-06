package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.CreateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.UpdateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseResponse;

import java.util.List;
import java.util.UUID;

public interface TestCaseService {
    TestCaseDetailResponse create(CreateTestCaseRequest request);

    List<TestCaseResponse> getByProjectId(UUID projectId);

    TestCaseDetailResponse getById(UUID id);

    TestCaseDetailResponse update(UUID id, UpdateTestCaseRequest request);

    void delete(UUID id);
}
