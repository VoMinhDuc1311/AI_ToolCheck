package com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentFailureAnalysisResponse {
    private UUID analysisId;
    private UUID testResultId;
    private String failureType;
    private String summary;
    private String rootCause;
    private String recommendedNextAction;
    private String priority;
    private Double confidence;
    private String modelName;
    private LocalDateTime createdAt;
}
