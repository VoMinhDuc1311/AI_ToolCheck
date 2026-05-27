package com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestFailureAnalysisDetailResponse {
    private UUID id;
    private UUID testResultId;
    private UUID aiJobLogId;
    private String modelName;
    private String failureType;
    private String summary;
    private String rootCause;
    private String expectedBehavior;
    private String actualBehavior;
    private Boolean isLikelyBackendBug;
    private Boolean isLikelyTestCaseBug;
    private String suggestedFixesJson;
    private String recommendedNextAction;
    private Double confidence;
    private String priority;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
