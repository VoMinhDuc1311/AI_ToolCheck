package com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for registering an external runtime.
 *
 * <p>An <em>external runtime</em> is a running application that the caller
 * is already managing (e.g. running locally or on a remote server). AI ToolCheck
 * will <em>not</em> try to build or start it; it simply records the base URL
 * so that TestRun execution can target it.
 *
 * <p>The provided {@code baseUrl} must be a reachable HTTP/HTTPS endpoint.
 * Validation (scheme, host, credentials) is enforced in the service layer via
 * {@link com.aitoolcheck.ai_toolcheck1_backend.common.RuntimeTargetUrlValidator}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterExternalRuntimeRequest {

    /**
     * The publicly reachable base URL of the running application.
     * Examples: {@code http://localhost:8081}, {@code https://api.example.com}.
     * Must use http or https. Must not contain credentials. Trailing slash is stripped.
     */
    @NotBlank(message = "baseUrl is required")
    @Size(max = 500, message = "baseUrl must not exceed 500 characters")
    private String baseUrl;

    /**
     * Optional human-readable label for this runtime (e.g. "local dev", "staging").
     * Stored in {@code container_name} column for display purposes.
     */
    @Size(max = 255, message = "label must not exceed 255 characters")
    private String label;
}
