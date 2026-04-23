package com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class UpdateTestCaseAssertionRequest {
    private AssertionType assertionType;
    private String targetPath;
    private ComparisonOperator operator;
    private String expectedValue;
}
