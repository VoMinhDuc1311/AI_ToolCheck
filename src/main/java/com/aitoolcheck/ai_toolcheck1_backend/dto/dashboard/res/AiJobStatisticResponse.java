package com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiJobStatisticResponse {
    private String jobType;
    private String executionStatus;
    private long totalJobs;
    private long totalInputToken;
    private long totalOutputToken;
    private long totalToken;
}
