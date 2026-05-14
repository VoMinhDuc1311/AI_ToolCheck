package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedTestCaseActualDto {
    private Integer actualStatus;
    private String actualResponseJson;
    private String maskedActualResponseJson;
    private Integer responseTimeMs;
    private String errorMessage;
    private ResultStatus resultStatus;
}
