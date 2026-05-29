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
 * Detailed response DTO for a TestCaseInput.
 *
 * <p>JSON-typed fields use {@code Map<String, Object>} instead of {@code JsonNode}
 * to guarantee clean JSON serialization in API responses. See {@link TestCaseInputResponse}.
 */
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class TestCaseInputDetailResponse {
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
