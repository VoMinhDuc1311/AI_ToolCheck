package com.aitoolcheck.ai_toolcheck1_backend.dto.ci.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for the CI Trigger endpoint (POST /v1/ci/trigger).
 * <p>
 * Callers (GitHub Actions, Jenkins, GitLab CI) supply project coordinates,
 * test case selection, and optional CI metadata. The endpoint creates a
 * TestRun and, when {@code runImmediately=true}, executes it synchronously
 * so that the pipeline can gate on a definitive COMPLETED/FAILED status.
 * <p>
 * environmentName must be an existing {@link EnvironmentType} value (e.g. DEV, STAGING).
 * The enum does not contain a "CI" value; callers should use DEV or STAGING.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CiTriggerRequest {

    @NotNull(message = "projectId is required")
    private UUID projectId;

    private List<UUID> testCaseIds;

    private Boolean includeAllActive;

    @NotBlank(message = "baseUrl is required")
    @Size(max = 500, message = "baseUrl must not exceed 500 characters")
    private String baseUrl;

    private EnvironmentType environmentName;

    private ExecutionMode executionMode;

    @Size(max = 150, message = "runName must not exceed 150 characters")
    private String runName;

    @Size(max = 100, message = "branchName must not exceed 100 characters")
    private String branchName;

    @Size(max = 80, message = "commitSha must not exceed 80 characters")
    private String commitSha;

    @Size(max = 100, message = "triggeredBy must not exceed 100 characters")
    private String triggeredBy;

    private Boolean runImmediately;
}
