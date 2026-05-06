package com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class TestCaseAssertionResponse {
    private UUID id;
    private UUID testCaseId;
    private AssertionType assertionType;
    private String targetPath;
    private ComparisonOperator operator;
    private String expectedValue;
    private Boolean enabledFlag;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
