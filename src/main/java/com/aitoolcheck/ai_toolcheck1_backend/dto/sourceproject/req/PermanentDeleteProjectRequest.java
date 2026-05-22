package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for the permanent delete danger-zone endpoint.
 *
 * <p>The caller must supply the exact {@code projectKey} of the project to be deleted
 * as an explicit confirmation. Any mismatch results in a 400 Bad Request.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class PermanentDeleteProjectRequest {

    @NotBlank(message = "confirmProjectKey is required")
    private String confirmProjectKey;
}
