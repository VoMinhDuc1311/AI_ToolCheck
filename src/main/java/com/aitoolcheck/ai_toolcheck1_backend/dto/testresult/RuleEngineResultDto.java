package com.aitoolcheck.ai_toolcheck1_backend.dto.testresult;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RuleEngineResultDto {
    private ResultStatus finalStatus;
    private String logDetails;
}