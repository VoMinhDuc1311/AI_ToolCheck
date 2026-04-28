package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;

import java.util.UUID;

public interface SourceAnalysisResultService {
    SourceAnalysisResultDetailResponse analyzeProject(UUID projectId);

    SourceAnalysisResultDetailResponse getByProjectId(UUID projectId);
}
