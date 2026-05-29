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

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for creating a TestCaseInput.
 *
 * <p>JSON-typed fields (queryParamsJson, headersJson, requestBodyJson) use
 * {@code Map<String, Object>} instead of {@code JsonNode} to avoid Jackson's
 * "Type definition error: [simple type, class JsonNode]" when deserializing
 * nested JSON objects in a @RequestBody. The service layer serializes these
 * maps to String before persisting and deserializes String back for responses.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateTestCaseInputRequest {

    private UUID testCaseId;

    @NotNull(message = "httpMethod is required")
    private HttpMethod httpMethod;

    @NotBlank(message = "requestPath is required")
    @Size(max = 500, message = "requestPath must not exceed 500 characters")
    private String requestPath;

    /** JSON object for query parameters. Accepts {@code {"name":"value"}} or null. */
    private Map<String, Object> queryParamsJson;

    /** JSON object for request headers. Accepts {@code {"Accept":"application/json"}} or null. */
    private Map<String, Object> headersJson;

    /** JSON object/array for request body. Null is valid for GET requests. */
    private Map<String, Object> requestBodyJson;

    @Size(max = 100, message = "contentType must not exceed 100 characters")
    private String contentType;

    @Min(value = 1000, message = "timeoutMs must be at least 1000")
    @Max(value = 120000, message = "timeoutMs must not exceed 120000")
    private Integer timeoutMs;

    private String inputData;
}
