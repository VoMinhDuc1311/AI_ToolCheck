package com.aitoolcheck.ai_toolcheck1_backend.service.runner;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dedicated technical component responsible for executing a single real HTTP
 * request described by a {@link PreparedHttpRequestResponse}.
 *
 * <p><strong>Responsibilities (this class only):</strong></p>
 * <ul>
 *   <li>Validate that {@code finalUrl} uses http or https.</li>
 *   <li>Apply method, headers, contentType, body, and timeoutMs.</li>
 *   <li>Execute the HTTP call via {@link RestTemplate}.</li>
 *   <li>Capture actualStatus, responseBody, responseTimeMs.</li>
 *   <li>Catch network/timeout errors and return {@link ExecutedHttpResponse}
 *       instead of throwing.</li>
 * </ul>
 *
 * <p><strong>Must NOT:</strong> persist anything, evaluate assertions, or build
 * prepared requests. Use {@link TestRequestBuilder} for building and
 * {@code TestResultService} for assertions and persistence.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestHttpExecutor {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Execute the real HTTP call described by {@code prepared}.
     *
     * @param prepared the fully-built request frame from {@link TestRequestBuilder}
     * @return an {@link ExecutedHttpResponse} — never {@code null}, never throws
     */
    public ExecutedHttpResponse execute(PreparedHttpRequestResponse prepared) {
        try {
            String finalUrl = prepared.getFinalUrl();
            if (!isHttpOrHttps(finalUrl)) {
                return ExecutedHttpResponse.networkError("finalUrl must use http or https: " + finalUrl);
            }

            HttpMethod method = resolveSpringHttpMethod(prepared.getMethod());
            if (method == null) {
                return ExecutedHttpResponse.networkError(
                        "Unsupported HTTP method: " + prepared.getMethod());
            }

            HttpHeaders headers = buildHeaders(prepared);
            HttpEntity<?> requestEntity = buildRequestEntity(prepared, headers, method);

            log.debug("[TestHttpExecutor] {} {}", method, finalUrl);
            long start = System.currentTimeMillis();

            ResponseEntity<String> response = restTemplate.exchange(
                    finalUrl, method, requestEntity, String.class);

            long elapsed = System.currentTimeMillis() - start;
            int statusCode = response.getStatusCode().value();
            String responseBody = response.getBody();
            Map<String, String> responseHeaders = extractHeaders(response.getHeaders());

            log.debug("[TestHttpExecutor] {} {} → {} in {}ms (body {} bytes)",
                    method, finalUrl, statusCode, elapsed,
                    responseBody != null ? responseBody.length() : 0);

            return ExecutedHttpResponse.success(statusCode, responseBody, elapsed, responseHeaders);

        } catch (HttpStatusCodeException e) {
            // 4xx / 5xx — a real HTTP response was received
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            Map<String, String> responseHeaders = extractHeaders(e.getResponseHeaders());
            log.warn("[TestHttpExecutor] HTTP {} from {}", statusCode, prepared.getFinalUrl());
            return ExecutedHttpResponse.httpError(
                    statusCode, responseBody,
                    "HTTP " + statusCode + " from target",
                    responseHeaders);

        } catch (ResourceAccessException e) {
            // Connection refused / DNS failure / socket timeout
            String msg = e.getMessage() != null ? e.getMessage() : "Network or timeout error";
            log.warn("[TestHttpExecutor] Network error for {}: {}", prepared.getFinalUrl(), msg);
            return ExecutedHttpResponse.networkError(truncateSafe(msg, 500));

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            log.error("[TestHttpExecutor] Unexpected error for {}: {}", prepared.getFinalUrl(), msg);
            return ExecutedHttpResponse.networkError("Unexpected error: " + truncateSafe(msg, 500));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private HttpMethod resolveSpringHttpMethod(
            com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod method) {
        if (method == null) return null;
        return switch (method) {
            case GET     -> HttpMethod.GET;
            case POST    -> HttpMethod.POST;
            case PUT     -> HttpMethod.PUT;
            case PATCH   -> HttpMethod.PATCH;
            case DELETE  -> HttpMethod.DELETE;
            case HEAD    -> HttpMethod.HEAD;
            case OPTIONS -> HttpMethod.OPTIONS;
        };
    }

    private HttpHeaders buildHeaders(PreparedHttpRequestResponse prepared) {
        HttpHeaders headers = new HttpHeaders();

        // Apply Content-Type: prefer explicit value, fall back to application/json
        if (hasText(prepared.getContentType())) {
            try {
                headers.setContentType(MediaType.parseMediaType(prepared.getContentType()));
            } catch (Exception ex) {
                log.debug("[TestHttpExecutor] Unparseable contentType '{}', defaulting to application/json",
                        prepared.getContentType());
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        // Apply custom headers from the prepared request (Map<String,Object>)
        Map<String, Object> customHeaders = prepared.getHeaders();
        if (customHeaders != null) {
            for (Map.Entry<String, Object> entry : customHeaders.entrySet()) {
                if (entry.getValue() != null) {
                    headers.set(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }

        return headers;
    }

    private HttpEntity<?> buildRequestEntity(
            PreparedHttpRequestResponse prepared, HttpHeaders headers, HttpMethod method) {

        if (isBodylessMethod(method)) {
            return new HttpEntity<>(headers);
        }

        // For POST / PUT / PATCH / DELETE with body
        // body is typed as Object (Map, List, primitive, or null)
        String bodyStr = null;
        Object bodyObj = prepared.getBody();
        if (bodyObj != null) {
            try {
                bodyStr = objectMapper.writeValueAsString(bodyObj);
            } catch (Exception ex) {
                log.warn("[TestHttpExecutor] Could not serialize body to JSON, using empty body");
            }
        }
        if (!hasText(bodyStr)) {
            bodyStr = "{}"; // safe empty body default
        }
        return new HttpEntity<>(bodyStr, headers);
    }

    private boolean isBodylessMethod(HttpMethod method) {
        return method == HttpMethod.GET
                || method == HttpMethod.HEAD
                || method == HttpMethod.OPTIONS;
    }

    private boolean isHttpOrHttps(String url) {
        if (!hasText(url)) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String truncateSafe(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    /**
     * Extracts HTTP headers from a Spring HttpHeaders map into a flat {@code Map<String,String>}.
     * Only the first value of each header is kept (sufficient for assertion matching).
     * Header names are kept as-is (mixed-case from server).
     *
     * @param httpHeaders the Spring HttpHeaders; may be null.
     * @return a non-null, possibly empty, flat map of header name → first value.
     */
    private Map<String, String> extractHeaders(org.springframework.http.HttpHeaders httpHeaders) {
        if (httpHeaders == null) {
            return Collections.emptyMap();
        }
        Map<String, String> flat = new LinkedHashMap<>();
        httpHeaders.forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                flat.put(name, values.get(0));
            }
        });
        return flat;
    }
}
