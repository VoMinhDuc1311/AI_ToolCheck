package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.FailedTestCaseAiPayload;

import java.util.List;
import java.util.UUID;

public interface FailedTestCaseCollectorService {

    /**
     * Thu thập tất cả các TestResult bị FAIL hoặc ERROR trong một TestRun,
     * thu thập các thông tin Request/Expected/Actual, mask dữ liệu nhạy cảm
     * và đóng gói thành Payload chuẩn bị gửi sang AI Skill 3.
     *
     * @param testRunId ID của đợt chạy test
     * @return Danh sách các payload chứa đủ dữ kiện để AI phân tích
     */
    List<FailedTestCaseAiPayload> collectFailedTestCasesForAi(UUID testRunId);

}
