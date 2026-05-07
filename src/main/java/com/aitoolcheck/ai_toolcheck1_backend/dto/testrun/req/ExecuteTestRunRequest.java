package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecuteTestRunRequest {

    @NotNull(message = "testRunId is required")
    private UUID testRunId;

    /** When true, prepare and validate the request but do not send it. */
    private Boolean dryRun;
}