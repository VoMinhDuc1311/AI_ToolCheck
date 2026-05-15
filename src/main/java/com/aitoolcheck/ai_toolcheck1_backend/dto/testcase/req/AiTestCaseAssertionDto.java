package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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

    private String assertionType;

    // Đã gỡ bỏ @NotBlank và @Pattern để linh hoạt xử lý các trường hợp như
    // STATUS_CODE (không cần jsonPath)
    private String jsonPath;

    private String comparisonOperator;

    private String expectedValue;
}