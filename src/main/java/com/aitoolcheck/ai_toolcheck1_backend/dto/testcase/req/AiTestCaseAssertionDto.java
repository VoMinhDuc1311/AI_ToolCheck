package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO cho các quy tắc kiểm tra (assertions) của test case do AI sinh ra.
 * Map với bảng test_case_assertion trong Database.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AiTestCaseAssertionDto {

    @NotNull(message = "Assertion type is required")
    private AssertionType assertionType;

    @NotBlank(message = "jsonPath không được để trống")
    @Pattern(regexp = "^\\$.*", message = "jsonPath bắt buộc phải bắt đầu bằng ký tự '$'")
    private String jsonPath;

    @NotNull(message = "Comparison operator is required")
    private ComparisonOperator comparisonOperator;

    private String expectedValue;
}