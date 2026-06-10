package com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for a TestCaseInput summary.
 *
 * <p>JSON-typed fields (queryParamsJson, headersJson, requestBodyJson) use
 * {@code Map<String, Object>} so Jackson serializes them as proper JSON objects
 * (e.g. {@code {"name":"ChatGPT"}}) rather than exposing JsonNode internal
 * metadata fields like {@code array, boolean, object, nodeType}.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TestCaseInputResponse {
    private UUID id;
    private UUID testCaseId;
    private HttpMethod httpMethod;
    private String requestPath;
    private Map<String, Object> queryParamsJson;
    private Map<String, Object> headersJson;
    private Map<String, Object> requestBodyJson;
    private String contentType;
    private Integer timeoutMs;
    private String inputData;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
