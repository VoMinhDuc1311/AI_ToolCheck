package com.aitoolcheck.ai_toolcheck1_backend.service.runner;

import java.util.Map;

/**
 * Internal DTO representing the result of one real HTTP execution.
 * <p>
 * Produced exclusively by {@link TestHttpExecutor}. The service layer maps
 * this to {@link com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.HttpActualResponseDto}
 * before passing it downstream to assertion evaluation and persistence.
 * </p>
 *
 * <ul>
 *   <li>{@code statusCode} – HTTP response status (e.g. 200, 404).
 *       {@code 0} when a network-level error prevented any response.</li>
 *   <li>{@code responseBody} – Raw response body string; may be {@code null}.</li>
 *   <li>{@code responseTimeMs} – Wall-clock time in ms from request send to
 *       response receipt. {@code 0} when no response was received.</li>
 *   <li>{@code errorMessage} – Human-readable error description on failure.
 *       {@code null} on success.</li>
 *   <li>{@code connectionError} – {@code true} when the target could not be
 *       reached (connection refused, DNS failure, timeout).</li>
 *   <li>{@code responseHeaders} – HTTP response headers as a flat string map.
 *       Key is the header name (may be mixed-case per server). {@code null}
 *       when no response was received (network error).</li>
 * </ul>
 */
public record ExecutedHttpResponse(
        int statusCode,
        String responseBody,
        long responseTimeMs,
        String errorMessage,
        boolean connectionError,
        Map<String, String> responseHeaders
) {

    /** Convenience factory for a clean success result. */
    public static ExecutedHttpResponse success(
            int statusCode, String responseBody, long responseTimeMs, Map<String, String> responseHeaders) {
        return new ExecutedHttpResponse(statusCode, responseBody, responseTimeMs, null, false, responseHeaders);
    }

    /** Convenience factory for a network/connection-level failure (no HTTP response). */
    public static ExecutedHttpResponse networkError(String errorMessage) {
        return new ExecutedHttpResponse(0, null, 0L, errorMessage, true, null);
    }

    /** Convenience factory for an HTTP-level error (4xx/5xx — response was received). */
    public static ExecutedHttpResponse httpError(
            int statusCode, String responseBody, String errorMessage, Map<String, String> responseHeaders) {
        return new ExecutedHttpResponse(statusCode, responseBody, 0L, errorMessage, false, responseHeaders);
    }
}

