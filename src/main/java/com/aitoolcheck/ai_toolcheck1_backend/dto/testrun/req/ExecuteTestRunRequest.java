package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
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
public class ExecuteTestRunRequest {

    private UUID projectId;
    private String runCode;
    private EnvironmentType environmentName;
    private ExecutionMode executionMode;
    private String baseUrl;
    private List<UUID> testCaseIds;
}