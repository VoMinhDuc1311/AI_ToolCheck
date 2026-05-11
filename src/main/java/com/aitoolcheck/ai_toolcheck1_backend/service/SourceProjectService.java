package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateProjectVisibilityRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectResponse;

import java.util.List;
import java.util.UUID;

public interface SourceProjectService {

    SourceProjectDetailResponse create(CreateSourceProjectRequest request);

    SourceProjectDetailResponse getById(UUID id);

    List<SourceProjectResponse> getAll();

    List<SourceProjectResponse> getMine();

    List<SourceProjectResponse> getPublic();

    SourceProjectDetailResponse update(UUID id, UpdateSourceProjectRequest request);

    SourceProjectDetailResponse updateVisibility(UUID id, UpdateProjectVisibilityRequest request);

    void delete(UUID id);
}
