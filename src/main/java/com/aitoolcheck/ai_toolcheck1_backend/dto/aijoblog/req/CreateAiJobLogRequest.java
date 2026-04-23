package com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
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

public class CreateAiJobLogRequest {
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
