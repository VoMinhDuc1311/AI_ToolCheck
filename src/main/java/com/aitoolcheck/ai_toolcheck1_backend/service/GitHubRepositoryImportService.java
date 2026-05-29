package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github.GitHubRepositoryImportResponse;

import java.util.UUID;

public interface GitHubRepositoryImportService {
    GitHubRepositoryImportResponse importRepository(UUID projectId);
}
