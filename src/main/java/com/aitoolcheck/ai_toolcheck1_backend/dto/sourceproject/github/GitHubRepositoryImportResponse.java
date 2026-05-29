package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GitHubRepositoryImportResponse {
    private UUID projectId;
    private String repositoryUrl;
    private String repositoryBranch;
    private String provider;
    private String owner;
    private String repositoryName;
    private String importStatus;
    private int importedFileCount;
    private int skippedFileCount;
    private int totalFileCount;
    private String message;
}
