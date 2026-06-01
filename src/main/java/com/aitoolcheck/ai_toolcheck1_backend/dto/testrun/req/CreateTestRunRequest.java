package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req;

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

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateTestRunRequest {

    @NotNull(message = "projectId is required")
    private UUID projectId;
    @NotBlank(message = "runName is required")
    @Size(max = 150, message = "runName must not exceed 150 characters")
    private String runName;
    @Size(max = 2000, message = "description must not exceed 2000 characters")
    private String description;
    /**
     * Runtime base URL of the target application.
     * Optional at the request level — if blank or omitted, TestRunServiceImpl
     * will fall back to the project's defaultTargetBaseUrl.
     * If neither is set, a 400 error is returned.
     * IMPORTANT: this must NEVER be auto-filled from repositoryUrl (GitHub source).
     */
    @Size(max = 500, message = "baseUrl must not exceed 500 characters")
    private String baseUrl;
    private EnvironmentType environmentName;
    private ExecutionMode executionMode;
    private List<UUID> testCaseIds;
    private Boolean includeAllActive;
}
