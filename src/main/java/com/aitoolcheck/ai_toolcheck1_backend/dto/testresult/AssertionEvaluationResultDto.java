package com.aitoolcheck.ai_toolcheck1_backend.dto.testresult;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssertionEvaluationResultDto {
    private UUID assertionId;
    private AssertionType assertionType;
    private String targetPath;
    private ComparisonOperator operator;
    private String expectedValue;
    private String actualValue;
    private boolean passed;
    private String message;
}
