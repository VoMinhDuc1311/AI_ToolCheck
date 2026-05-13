package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Wrapper DTO for receiving a collection of AI-generated test cases.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AiGeneratedTestCaseRequest {

    @NotEmpty(message = "Test cases list cannot be empty")
    @Valid
    @Builder.Default
    private List<AiTestCaseItemDto> testCases = new ArrayList<>();
}
