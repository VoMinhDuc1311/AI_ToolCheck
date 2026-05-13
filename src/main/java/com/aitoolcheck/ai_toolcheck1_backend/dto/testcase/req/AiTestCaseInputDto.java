package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for AI-generated test case inputs.
 * Maps to test_case_input table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AiTestCaseInputDto {

    @NotNull(message = "Parameter location (paramIn) is required")
    private ParamIn paramIn;

    // Đã thay đổi từ String sang JsonNode để hứng cấu trúc JSON Object động từ Gemini/Ollama
    private JsonNode payload;
}