package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
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
public class UpdateTestRunRequest {

    @Size(max = 150, message = "runName must not exceed 150 characters")
    private String runName;

    @Size(max = 2000, message = "description must not exceed 2000 characters")
    private String description;

    @Size(max = 500, message = "baseUrl must not exceed 500 characters")
    private String baseUrl;

    private EnvironmentType environmentName;

    private ExecutionMode executionMode;
}
