package com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res;

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
public class AnalyzeFailuresResponse {
    private UUID testRunId;
    private int totalFailedFound;
    private int totalSubmitted;
    private int totalSuccess;
    private int totalFailed;
    private int totalSkipped;
    private List<AnalyzeFailureItemResponse> items;
    private String message;
}
