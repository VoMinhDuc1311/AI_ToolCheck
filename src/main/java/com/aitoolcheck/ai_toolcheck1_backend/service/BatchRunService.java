package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req.CreateBatchRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunReportResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res.BatchRunResponse;

import java.util.List;
import java.util.UUID;

public interface BatchRunService {

    BatchRunResponse create(CreateBatchRunRequest request);

    BatchRunResponse start(UUID id);

    BatchRunResponse cancel(UUID id);

    BatchRunResponse getById(UUID id);

    List<BatchRunItemResponse> getItems(UUID id);

    BatchRunReportResponse getReport(UUID id);
}
