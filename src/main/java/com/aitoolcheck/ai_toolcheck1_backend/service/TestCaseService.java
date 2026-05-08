package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.CreateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.GenerateTestCaseRequest;
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

    UUID generateTestCaseAsync(GenerateTestCaseRequest request);

    String generateTestCaseProcessing(String endpointId, UUID jobId);

    /**
     * Phân rã và lưu trữ danh sách test case sinh bởi AI vào DB.
     *
     * @param rawJson     Chuỗi JSON thô từ AI.
     * @param endpointId  UUID của ApiEndpoint để liên kết.
     */
    void persistTestCasesFromAi(String rawJson, UUID endpointId);
}
