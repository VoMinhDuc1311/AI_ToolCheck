package com.aitoolcheck.ai_toolcheck1_backend.service.runner;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.HttpActualResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class TestRequestBuilder {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

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

        String normalizedBase = normalizeBaseUrl(baseUrl);
        String normalizedPath = normalizeRequestPath(input.getRequestPath());
        String finalUrl = buildFinalUrl(normalizedBase, normalizedPath);

        JsonNode parsedHeaders = parseJsonOrNull(input.getHeadersJson(), "headersJson");
        JsonNode parsedQueryParams = parseJsonOrNull(input.getQueryParamsJson(), "queryParamsJson");
        JsonNode parsedBody = parseJsonOrNull(input.getRequestBodyJson(), "requestBodyJson");

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

    /**
     * Execute an HTTP request to the target server and capture the actual response.
     * This method handles all HTTP status codes (2xx, 3xx, 4xx, 5xx) and network
     * failures gracefully.
     *
     * @param baseUrl    the base URL of the target server (e.g.
     *                   {@code http://localhost:8080})
     * @param path       the request path (e.g. {@code /api/users}) - must start
     *                   with '/'
     * @param httpMethod the HTTP method as a String (e.g. {@code GET},
     *                   {@code POST}, {@code PUT})
     * @param jsonBody   the request body as JSON string (used only for
     *                   POST/PUT/PATCH)
     * @return {@link HttpActualResponseDto} containing status code, response body,
     *         response time, and error message
     *         Never throws exception - returns error information in the DTO instead
     */
    public HttpActualResponseDto executeRequest(
            String baseUrl,
            String path,
            String httpMethod,
            String jsonBody,
            String queryParamsJson) {

        try {
            // Step 1: Build and validate final URL
            String normalizedBase = normalizeBaseUrl(baseUrl);
            String normalizedPath = normalizeRequestPath(path);
            String finalUrl = buildFinalUrl(normalizedBase, normalizedPath);

            // Step 2: Append Query Parameters if present
            org.springframework.web.util.UriComponentsBuilder builder = org.springframework.web.util.UriComponentsBuilder.fromUriString(finalUrl);
            JsonNode queryNode = parseJsonOrNull(queryParamsJson, "queryParamsJson");
            if (queryNode != null && queryNode.isObject()) {
                java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = queryNode.fields();
                while (fields.hasNext()) {
                    java.util.Map.Entry<String, JsonNode> field = fields.next();
                    builder.queryParam(field.getKey(), field.getValue().asText());
                }
            }
            finalUrl = builder.build().toUriString();

            log.debug("Executing {} request to {}", httpMethod, finalUrl);

            // Step 2: Map httpMethod String to Spring HttpMethod enum
            HttpMethod method;
            try {
                method = HttpMethod.valueOf(httpMethod.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.error("Invalid HTTP method: {}", httpMethod);
                return HttpActualResponseDto.builder()
                        .statusCode(0)
                        .errorMessage("Invalid HTTP method: " + httpMethod)
                        .responseTimeMs(0L)
                        .build();
            }

            // Step 3: Prepare headers with defaults
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));

            // Step 4: Prepare request entity
            HttpEntity<?> requestEntity;
            if (isBodylessMethod(method)) {
                // GET, DELETE, HEAD, OPTIONS: no body
                requestEntity = new HttpEntity<>(headers);
                log.debug("Request body omitted for {} method", method);
            } else {
                // POST, PUT, PATCH, etc: include body
                if (!hasText(jsonBody)) {
                    jsonBody = "{}"; // Default empty JSON object
                }
                requestEntity = new HttpEntity<>(jsonBody, headers);
                log.debug("Request body size: {} bytes", jsonBody.length());
            }

            // Step 5: Start timing
            long startTime = System.currentTimeMillis();

            // Step 6: Execute request and capture response
            log.info("Sending {} request to: {}", method, finalUrl);
            ResponseEntity<String> response = restTemplate.exchange(
                    finalUrl,
                    method,
                    requestEntity,
                    String.class);

            // Step 7: Stop timing
            long endTime = System.currentTimeMillis();
            long responseTimeMs = endTime - startTime;

            // Step 8: Extract response details
            String responseBody = response.getBody();
            HttpStatusCode statusCodeObj = response.getStatusCode();
            int statusCode = statusCodeObj.value();

            log.info("Response received - Status: {}, Time: {}ms, Body size: {} bytes",
                    statusCode,
                    responseTimeMs,
                    (responseBody != null ? responseBody.length() : 0));

            return HttpActualResponseDto.builder()
                    .statusCode(statusCode)
                    .responseBody(responseBody)
                    .responseTimeMs(responseTimeMs)
                    .errorMessage(null)
                    .build();

        } catch (HttpClientErrorException e) {
            // 4xx errors: Client error (Bad Request, Not Found, etc.)
            log.warn("HTTP 4xx Client Error - Status: {}", e.getStatusCode());
            return buildErrorResponse(e, "Client");

        } catch (HttpServerErrorException e) {
            // 5xx errors: Server error (Internal Server Error, Service Unavailable, etc.)
            log.warn("HTTP 5xx Server Error - Status: {}", e.getStatusCode());
            return buildErrorResponse(e, "Server");

        } catch (HttpStatusCodeException e) {
            // Other HTTP error codes (3xx redirects, etc.)
            log.warn("HTTP Error - Status: {}", e.getStatusCode());
            return buildErrorResponse(e, "HTTP");

        } catch (ResourceAccessException e) {
            // Network error: connection refused, timeout, socket timeout, etc.
            long responseTimeMs = 0;
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Network or timeout error";

            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                log.error("Request timeout - Message: {}", errorMsg);
            } else if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                log.error("Connection refused to target server");
            } else {
                log.error("Network access error: {}", errorMsg);
            }

            return HttpActualResponseDto.builder()
                    .statusCode(0) // 0 indicates connection/network failure
                    .responseBody(null)
                    .responseTimeMs(responseTimeMs)
                    .errorMessage(errorMsg)
                    .build();

        } catch (Exception e) {
            // Unexpected error
            log.error("Unexpected error executing HTTP request", e);
            return HttpActualResponseDto.builder()
                    .statusCode(0)
                    .responseBody(null)
                    .responseTimeMs(0L)
                    .errorMessage("Unexpected error: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Helper method to build error response from HttpStatusCodeException.
     * Extracts status code and response body from the exception.
     */
    private HttpActualResponseDto buildErrorResponse(HttpStatusCodeException e, String errorType) {
        int statusCode = e.getStatusCode().value();
        String responseBody = e.getResponseBodyAsString();
        String errorMessage = String.format("%s Error (HTTP %d)", errorType, statusCode);

        log.debug("Error response body: {}", responseBody);

        return HttpActualResponseDto.builder()
                .statusCode(statusCode)
                .responseBody(responseBody)
                .responseTimeMs(0L) // Timing info not available from exception
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Check if HTTP method should NOT include a request body.
     */
    private boolean isBodylessMethod(HttpMethod method) {
        return method == HttpMethod.GET ||
                method == HttpMethod.DELETE ||
                method == HttpMethod.HEAD ||
                method == HttpMethod.OPTIONS;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation and Parsing Helpers
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
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException(fieldName + " is invalid JSON");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}