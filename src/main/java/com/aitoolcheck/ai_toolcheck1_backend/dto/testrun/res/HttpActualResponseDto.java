package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO containing the actual HTTP response result from executing a test request.
 * Used to capture both successful responses (2xx, 3xx, 4xx, 5xx) and network failures.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HttpActualResponseDto {

    /**
     * HTTP status code returned by the target server.
     * Examples: 200, 404, 500, etc.
     * If connection fails or timeout occurs, this is null or 0.
     */
    private Integer statusCode;

    /**
     * Response body as a raw JSON string.
     * If no body or error occurs, this may be null or empty string.
     */
    private String responseBody;

    /**
     * Response time in milliseconds.
     * Measured from when request was sent until response was received.
     * If connection fails before any response, this may be null or partial.
     */
    private Long responseTimeMs;

    /**
     * Error message in case of network failure, timeout, or connection exception.
     * Examples: "Connection refused", "Read timed out", "Unknown host"
     * This field is populated ONLY when statusCode is null/0.
     */
    private String errorMessage;
}
