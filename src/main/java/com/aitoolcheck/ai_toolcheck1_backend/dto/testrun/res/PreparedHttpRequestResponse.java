package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Represents a fully-prepared HTTP request frame, ready for execution or display.
 *
 * <p>JSON-typed fields use {@code Map<String, Object>} instead of {@code JsonNode} so that
 * Jackson serializes them as clean JSON objects in API responses (e.g. {@code {"name":"ChatGPT"}})
 * rather than exposing internal JsonNode metadata fields like {@code nodeType}, {@code array}, etc.
 *
 * <p>{@code body} is typed as {@code Object} to accommodate JSON objects, arrays, primitives,
 * or null for bodyless methods (GET/HEAD/OPTIONS).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreparedHttpRequestResponse {
    private HttpMethod method;
    private String finalUrl;
    private String baseUrl;
    private String requestPath;

    /** HTTP headers as a flat key-value map. Example: {@code {"Accept":"application/json"}}. */
    private Map<String, Object> headers;

    /** URL query parameters as a flat key-value map. Example: {@code {"name":"ChatGPT"}}. */
    private Map<String, Object> queryParams;

    /**
     * Request body. May be a JSON object ({@code Map<String,Object>}), array ({@code List}),
     * primitive, or {@code null} for bodyless HTTP methods.
     */
    private Object body;

    private String contentType;
    private Integer timeoutMs;
}
