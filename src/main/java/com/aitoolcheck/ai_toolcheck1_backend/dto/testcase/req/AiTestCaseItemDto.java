package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single AI-generated test case item.
 * Maps to test_case table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AiTestCaseItemDto {

    @NotBlank(message = "Test name is required")
    private String testName;

    @NotNull(message = "Case type is required")
    private CaseType caseType;

    @NotNull(message = "Priority level is required")
    private PriorityLevel priority;

    @NotNull(message = "HTTP method is required")
    private HttpMethod httpMethod;

    @NotBlank(message = "URL is required")
    private String url;

    private Integer expectedStatusCode;

    @Builder.Default
    @Valid
    private List<AiTestCaseInputDto> inputs = new ArrayList<>();

    @Builder.Default
    @Valid
    private List<AiTestCaseAssertionDto> assertions = new ArrayList<>();
}
