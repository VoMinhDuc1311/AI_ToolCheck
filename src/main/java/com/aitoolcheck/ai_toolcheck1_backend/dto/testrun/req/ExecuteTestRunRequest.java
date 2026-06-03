package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * DTO for initializing a test run execution.
 * Used when user triggers "Execute Test Run" action with selected test cases.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecuteTestRunRequest {

    @NotNull(message = "projectId is required")
    private UUID projectId;

    @Size(max = 150, message = "runName must not exceed 150 characters")
    private String runName;

    @Size(max = 2000, message = "description must not exceed 2000 characters")
    private String description;

    @Size(max = 100, message = "runCode must not exceed 100 characters")
    private String runCode;

    private EnvironmentType environmentName;

    private ExecutionMode executionMode;

    @Size(max = 500, message = "baseUrl must not exceed 500 characters")
    private String baseUrl;

    private RuntimeMode runtimeMode;

    @NotEmpty(message = "testCaseIds must not be empty")
    private List<UUID> testCaseIds;

    /** When true, prepare and validate the request but do not send it. */
    private Boolean dryRun;
}
