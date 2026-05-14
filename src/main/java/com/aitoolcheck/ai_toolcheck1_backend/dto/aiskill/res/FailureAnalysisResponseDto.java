package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailureAnalysisResponseDto {
    private String summary;
    private String failureType;
    private String rootCause;
    private String expectedBehavior;
    private String actualBehavior;
    private Boolean isLikelyBackendBug;
    private Boolean isLikelyTestCaseBug;
    private List<FailureSuggestedFixDto> suggestedFixes;
    private String recommendedNextAction;
    private Double confidence;
    private String priority;
    private String rawAiResponse; // Internal field to store the raw string before any DB persistence
    private UUID aiJobLogId;
}
