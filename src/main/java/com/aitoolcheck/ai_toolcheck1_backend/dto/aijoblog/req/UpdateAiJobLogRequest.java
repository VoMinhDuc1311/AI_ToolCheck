package com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;

import java.util.UUID;

public class UpdateAiJobLogRequest {
    private UUID projectId;
    private UUID apiEndpointId;
    private UUID testResultId;
    private UUID aiSkillId;
    private JobType jobType;
    private String modelName;
    private Integer tokenInput;
    private Integer tokenOutput;
    private ExecutionStatus executionStatus;
}
