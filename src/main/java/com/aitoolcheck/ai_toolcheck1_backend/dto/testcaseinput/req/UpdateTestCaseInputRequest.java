package com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.databind.JsonNode;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class UpdateTestCaseInputRequest {
    @NotNull(message = "httpMethod is required")
    private HttpMethod httpMethod;

    @NotBlank(message = "requestPath is required")
    @Size(max = 500, message = "requestPath must not exceed 500 characters")
    private String requestPath;

    private JsonNode queryParamsJson;

    private JsonNode headersJson;

    private JsonNode requestBodyJson;

    @Size(max = 100, message = "contentType must not exceed 100 characters")
    private String contentType;

    @Min(value = 1000, message = "timeoutMs must be at least 1000")
    @Max(value = 120000, message = "timeoutMs must not exceed 120000")
    private Integer timeoutMs;

    private String inputData;
}
