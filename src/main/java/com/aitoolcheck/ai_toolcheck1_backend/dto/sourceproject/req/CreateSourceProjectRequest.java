package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateSourceProjectRequest {

    @NotBlank(message = "projectKey must not be blank")
    @Size(max = 100, message = "projectKey must be at most 100 characters")
    private String projectKey;

    @NotBlank(message = "projectName must not be blank")
    @Size(max = 255, message = "projectName must be at most 255 characters")
    private String projectName;

    @Size(max = 1000, message = "description must be at most 1000 characters")
    private String description;

    /** Optional GitHub repository URL. Will be validated and normalised server-side. */
    @Size(max = 500, message = "repositoryUrl must be at most 500 characters")
    private String repositoryUrl;

    /** Optional branch name (e.g. main, develop). Validated server-side. */
    @Size(max = 120, message = "repositoryBranch must be at most 120 characters")
    private String repositoryBranch;

    /**
     * Optional runtime base URL of the analysed application.
     * Example: http://52.220.34.212:8081
     * <p>
     * DISTINCT from repositoryUrl. repositoryUrl = GitHub source for static analysis.
     * defaultTargetBaseUrl = live HTTP endpoint for test execution.
     * Validated server-side (must start with http:// or https:// when present).
     */
    @Size(max = 500, message = "defaultTargetBaseUrl must be at most 500 characters")
    private String defaultTargetBaseUrl;

    @NotNull(message = "backendType must not be null")
    private BackendType backendType;
}