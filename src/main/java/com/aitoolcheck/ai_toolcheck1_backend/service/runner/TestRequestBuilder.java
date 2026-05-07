package com.aitoolcheck.ai_toolcheck1_backend.service.runner;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;


@Component
@RequiredArgsConstructor
public class TestRequestBuilder {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final JsonMapper jsonMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a {@link PreparedHttpRequestResponse} from a base URL and a TestCaseInput entity.
     *
     * @param baseUrl the TestRun base URL (e.g. {@code http://localhost:8080})
     * @param input   the TestCaseInput loaded from the database
     * @return a fully populated frame object; never null
     * @throws BadRequestException if any input is invalid
     */
    public PreparedHttpRequestResponse build(String baseUrl, TestCaseInput input) {
        validateInput(input);

        String normalizedBase = normalizeBaseUrl(baseUrl);
        String normalizedPath = normalizeRequestPath(input.getRequestPath());
        String finalUrl = buildFinalUrl(normalizedBase, normalizedPath);

        JsonNode parsedHeaders    = parseJsonOrNull(input.getHeadersJson(),      "headersJson");
        JsonNode parsedQueryParams = parseJsonOrNull(input.getQueryParamsJson(),  "queryParamsJson");
        JsonNode parsedBody       = parseJsonOrNull(input.getRequestBodyJson(),   "requestBodyJson");

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

        // Strip trailing slash
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
        // normalizedPath always starts with '/' (validated earlier); strip it
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



    private JsonNode parseJsonOrNull(String json, String fieldName) {
        if (!hasText(json)) {
            return null;
        }

        try {
            return jsonMapper.readTree(json);
        } catch (JacksonException ex) {
            throw new BadRequestException(fieldName + " is invalid JSON");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
