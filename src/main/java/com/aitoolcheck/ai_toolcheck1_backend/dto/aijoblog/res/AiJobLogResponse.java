package com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class AiJobLogResponse {
    private UUID id;
    private UUID projectId;
    private UUID apiEndpointId;
    private UUID testResultId;
    private UUID aiSkillId;
    private JobType jobType;
    private String modelName;
    private ExecutionStatus executionStatus;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    /** Nullable — only set for LEGACY_INFERENCE jobs created via generate-docs-from-source. */
    private String errorMessage;
    private UUID scanBatchId;
}
