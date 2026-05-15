package com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeFailureItemResponse {
    private UUID testResultId;
    private UUID testCaseId;
    private UUID analysisId;
    private UUID aiJobLogId;
    private String status;
    private String failureType;
    private String summary;
    private Double confidence;
    private String priority;
    private String errorMessage;
}
