package com.aitoolcheck.ai_toolcheck1_backend.repository.projection;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;

public interface AiJobStatisticProjection {
    JobType getJobType();
    ExecutionStatus getExecutionStatus();
    Long getTotalJobs();
    Long getTotalInputToken();
    Long getTotalOutputToken();
    Long getTotalToken();
}
