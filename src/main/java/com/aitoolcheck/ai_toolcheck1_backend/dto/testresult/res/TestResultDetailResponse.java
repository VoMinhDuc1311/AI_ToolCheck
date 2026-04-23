package com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class TestResultDetailResponse {
    private UUID id;
    private UUID testRunItemId;
    private Integer actualStatus;
    private ResultStatus resultStatus;
    private Integer responseTime;
    private String actualResponseJson;
    private String errorMessage;
    private String blockedReason;

}
