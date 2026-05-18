package com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOverviewResponse {
    private long totalTests;
    private long totalPass;
    private long totalFail;
    private long totalError;
    private double passRate;
    private double failRate;
    private double errorRate;
    private long totalAiJobs;
    private long totalTokenInput;
    private long totalTokenOutput;
    private long totalTokenUsed;
    private long totalFailureAnalysis;
}
