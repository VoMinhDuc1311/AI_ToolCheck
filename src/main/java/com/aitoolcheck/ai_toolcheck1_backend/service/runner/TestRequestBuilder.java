package com.aitoolcheck.ai_toolcheck1_backend.service.runner;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link PreparedHttpRequestResponse} from a TestRun base URL and a
 * {@link TestCaseInput} entity.
 *
 * <p><strong>Responsibilities (this class only):</strong></p>
 * <ul>
 *   <li>Validate and normalise baseUrl and requestPath.</li>
 *   <li>Parse headersJson / queryParamsJson from stored JSON Strings to {@code Map<String,Object>}.</li>
 *   <li>Parse requestBodyJson to {@code Object} (supports objects, arrays, primitives, null).</li>
 *   <li>Return a fully populated {@link PreparedHttpRequestResponse}.</li>
 * </ul>
 *
 * <p><strong>Must NOT:</strong> execute HTTP, persist data, or evaluate
 * assertions. HTTP execution is the responsibility of {@link TestHttpExecutor}.</p>
 *
 * <p><strong>Design note:</strong> JSON-typed fields use {@code Map<String,Object>} (not JsonNode)
 * so that Jackson serializes them as clean JSON objects in API responses, not as JsonNode bean
 * metadata fields ({@code nodeType}, {@code array}, etc.).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestRequestBuilder {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** TypeReference for deserializing JSON objects to Map<String,Object>. */
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a {@link PreparedHttpRequestResponse} from a base URL and a
     * TestCaseInput entity.
     *
     * @param baseUrl the TestRun base URL (e.g. {@code http://localhost:8080})
     * @param input   the TestCaseInput loaded from the database
     * @return a fully populated frame object; never null
     * @throws BadRequestException if any input is invalid
     */
    public PreparedHttpRequestResponse build(String baseUrl, TestCaseInput input) {
        validateInput(input);

        String normalizedBase  = normalizeBaseUrl(baseUrl);
        String normalizedPath  = normalizeRequestPath(input.getRequestPath());
        String finalUrl        = buildFinalUrl(normalizedBase, normalizedPath);

        // Parse stored JSON strings to typed Map/Object — NOT to raw JsonNode,
        // which would cause Jackson to serialize internal bean metadata in responses.
        Map<String, Object> parsedHeaders     = parseJsonToMap(input.getHeadersJson(),     "headersJson");
        Map<String, Object> parsedQueryParams = parseJsonToMap(input.getQueryParamsJson(), "queryParamsJson");
        Object              parsedBody        = parseJsonToObject(input.getRequestBodyJson(), "requestBodyJson");

        return PreparedHttpRequestResponse.builder()
                .method(input.getHttpMethod())
                .finalUrl(finalUrl)
                .baseUrl(normalizedBase)
                .requestPath(normalizedPath)
                .headers(parsedHeaders)
                .queryParams(parsedQueryParams)
                .body(parsedBody)
                .contentType(input.getContentType())
                .timeoutMs(input.getTimeoutMs())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation and parsing helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void validateInput(TestCaseInput input) {
        if (input == null) {
            throw new BadRequestException("testCaseInput is required");
        }

        if (!hasText(input.getRequestPath())) {
            throw new BadRequestException("requestPath is required");
        }

        if (!input.getRequestPath().trim().startsWith("/")) {
            throw new BadRequestException("requestPath must start with '/'");
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new BadRequestException("baseUrl is required");
        }

        validateHttpUrl(baseUrl.trim());

        // Strip trailing slash — consistent with TestHttpExecutor URL construction
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeRequestPath(String requestPath) {
        return requestPath.trim();
    }

    private String buildFinalUrl(String normalizedBase, String normalizedPath) {
        // normalizedPath always starts with '/' (validated above); strip it
        // so the join produces exactly one separator between base and path.
        return normalizedBase + "/" + normalizedPath.substring(1);
    }

    private void validateHttpUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new BadRequestException("baseUrl is invalid");
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new BadRequestException("baseUrl is invalid");
        }

        if (!ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new BadRequestException("baseUrl must use http or https");
        }

        // Reject relative URIs (no host)
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BadRequestException("baseUrl is invalid");
        }
    }

    /**
     * Parses a stored JSON String into a {@code Map<String, Object>}.
     * Used for object-type fields: headersJson, queryParamsJson.
     *
     * @return parsed Map, or {@code null} if the string is blank/null
     * @throws BadRequestException if the stored string is not valid JSON or is not a JSON object
     */
    private Map<String, Object> parseJsonToMap(String json, String fieldName) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE_REF);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException(fieldName + " is invalid JSON or not a JSON object");
        }
    }

    /**
     * Parses a stored JSON String into a plain Java {@code Object}.
     * Used for requestBodyJson which may be an object, array, primitive, or null.
     *
     * @return parsed value as Map, List, String, Number, Boolean, or {@code null}
     * @throws BadRequestException if the stored string is not valid JSON
     */
    private Object parseJsonToObject(String json, String fieldName) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException(fieldName + " is invalid JSON");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}