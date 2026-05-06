package com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

public class CreateTestCaseAssertionRequest {
    private UUID testCaseId;

    @NotNull(message = "assertionType is required")
    private AssertionType assertionType;

    @Size(max = 500, message = "targetPath must not exceed 500 characters")
    private String targetPath;

    @NotNull(message = "operator is required")
    private ComparisonOperator operator;

    private String expectedValue;

    private Boolean enabledFlag;

    @NotNull(message = "sortOrder is required")
    @Min(value = 1, message = "sortOrder must be at least 1")
    private Integer sortOrder;
}
