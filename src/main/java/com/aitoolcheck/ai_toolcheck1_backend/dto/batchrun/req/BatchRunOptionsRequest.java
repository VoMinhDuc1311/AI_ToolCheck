package com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchRunOptionsRequest {

    private Boolean generateOpenApi;
    private Boolean generateTestCases;
    private Boolean startRuntime;
    private Boolean executeTestRun;
    private Boolean stopRuntimeAfterRun;

    @Min(value = 1, message = "maxConcurrency must be at least 1")
    @Max(value = 10, message = "maxConcurrency must not exceed 10")
    private Integer maxConcurrency;

    @Min(value = 0, message = "maxRetries must be at least 0")
    @Max(value = 3, message = "maxRetries must not exceed 3")
    private Integer maxRetries;

    private ExecutionMode executionMode;
    private BuildStrategy buildStrategy;

    @Size(max = 500, message = "externalBaseUrl must not exceed 500 characters")
    private String externalBaseUrl;

    public boolean shouldGenerateOpenApi() {
        return generateOpenApi == null || generateOpenApi;
    }

    public boolean shouldGenerateTestCases() {
        return generateTestCases == null || generateTestCases;
    }

    public boolean shouldStartRuntime() {
        return startRuntime == null || startRuntime;
    }

    public boolean shouldExecuteTestRun() {
        return executeTestRun == null || executeTestRun;
    }

    public boolean shouldStopRuntimeAfterRun() {
        return stopRuntimeAfterRun != null && stopRuntimeAfterRun;
    }

    public int safeMaxConcurrency() {
        return 1;
    }

    public int safeMaxRetries() {
        return maxRetries == null ? 1 : Math.max(0, Math.min(maxRetries, 3));
    }

    public ExecutionMode safeExecutionMode() {
        return executionMode == null ? ExecutionMode.READ_ONLY : executionMode;
    }

    public BuildStrategy safeBuildStrategy() {
        return buildStrategy == null ? BuildStrategy.AUTO : buildStrategy;
    }
}
