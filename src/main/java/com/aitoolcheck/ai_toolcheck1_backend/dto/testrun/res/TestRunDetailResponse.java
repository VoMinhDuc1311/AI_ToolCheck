package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrunitem.res.TestRunItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestRunDetailResponse {

    private UUID id;
    private UUID projectId;
    private String runCode;
    private String runName;
    private String description;
    private String baseUrl;
    private EnvironmentType environmentName;
    private ExecutionMode executionMode;
    private RuntimeMode runtimeMode;
    private UUID sourceRuntimeId;
    private String targetBaseUrlUsed;
    private String runtimeStatusAtStart;
    private String preflightStatus;
    private String preflightSummary;
    private RunStatus runStatus;
    private Integer totalItems;
    private List<TestRunItemResponse> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
