package com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiJobStatisticResponse {
    private long totalJobs;
    private long totalSuccessfulJobs;
    private long totalFailedJobs;
    private long totalTokenInput;
    private long totalTokenOutput;
}
