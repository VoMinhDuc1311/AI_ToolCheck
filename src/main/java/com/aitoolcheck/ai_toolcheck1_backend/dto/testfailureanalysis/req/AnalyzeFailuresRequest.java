package com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeFailuresRequest {
    private Boolean reAnalyze;
    private Boolean includeErrorStatus;
    private Integer maxItems;
    private Long delayMsBetweenCalls;
    private Boolean returnExistingWhenSkipped;
}
