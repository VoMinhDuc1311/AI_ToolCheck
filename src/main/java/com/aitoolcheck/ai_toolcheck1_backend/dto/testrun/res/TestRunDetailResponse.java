package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class TestRunDetailResponse {
    private UUID id;
    private UUID projectId;
    private String runCode;
    private EnvironmentType environmentName;
    private ExecutionMode executionMode;
    private String baseUrl;
    private RunStatus runStatus;
}
