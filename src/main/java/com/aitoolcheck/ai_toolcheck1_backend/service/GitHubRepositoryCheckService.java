package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github.GitHubRepositoryCheckResponse;

import java.util.UUID;

/**
 * Checks whether the public GitHub repository linked to a
 * {@code SourceProject} is reachable and detects basic project metadata.
 *
 * <p>Strict constraints:
 * <ul>
 *   <li>Only public repositories are supported.
 *   <li>No GitHub OAuth, no tokens, no cloning.
 *   <li>The repository URL is sourced exclusively from the project entity —
 *       never from the caller.
 * </ul>
 */
public interface GitHubRepositoryCheckService {

    /**
     * Performs a lightweight reachability and metadata-detection check for
     * the GitHub repository linked to the specified project.
     *
     * @param projectId the UUID of the {@code SourceProject} to check.
     * @return a {@link GitHubRepositoryCheckResponse} describing the outcome.
     */
    GitHubRepositoryCheckResponse check(UUID projectId);
}
