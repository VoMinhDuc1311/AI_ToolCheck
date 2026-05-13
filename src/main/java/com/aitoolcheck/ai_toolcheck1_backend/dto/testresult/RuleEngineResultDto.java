package com.aitoolcheck.ai_toolcheck1_backend.dto.testresult;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
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
public class RuleEngineResultDto {
    private UUID testResultId;
    private UUID testCaseId;
    private ResultStatus finalStatus;
    private int totalAssertions;
    private int passedAssertions;
    private int failedAssertions;
    private List<AssertionEvaluationResultDto> assertionResults;
    private String summaryMessage;
    private String logDetails;
}