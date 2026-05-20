package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.ci.req.CiTriggerRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.ci.res.CiTriggerResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.CiTriggerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Controller for CI/CD pipeline integration.
 * <p>
 * Public production URL: POST https://aitoolcheck-md.duckdns.org/api/v1/ci/trigger
 * <p>
 * Authentication: Bearer JWT required (falls through to anyRequest().authenticated()
 * in SecurityConfig — no change to SecurityConfig needed).
 * Authorization: enforced by ProjectAccessService inside CiTriggerService / TestRunService.
 * Requires at least MAINTAINER or EDITOR project role to create a TestRun;
 * MAINTAINER only for SAFE_WRITE or FULL_WRITE execution modes.
 */
@RestController
@RequestMapping("/v1/ci")
@RequiredArgsConstructor
@Tag(name = "CI Trigger", description = "CI/CD pipeline integration APIs")
public class CiTriggerController {

    private final CiTriggerService ciTriggerService;

    @PostMapping("/trigger")
    @Operation(
            summary = "CI trigger: create and optionally execute a TestRun",
            description = "Creates a TestRun from project/test-case coordinates supplied by an external "
                    + "CI/CD pipeline (GitHub Actions, Jenkins, GitLab CI). "
                    + "When runImmediately=true, executes the run synchronously and returns the "
                    + "definitive COMPLETED or FAILED status in a single response. "
                    + "Requires Bearer JWT authentication. "
                    + "environmentName must be an existing EnvironmentType value (e.g. DEV, STAGING).",
            operationId = "ciTrigger"
    )
    public ResponseEntity<ApiResponse<CiTriggerResponse>> trigger(
            @Valid @RequestBody CiTriggerRequest request) {

        CiTriggerResponse response = ciTriggerService.trigger(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<CiTriggerResponse>builder()
                        .code("SUCCESS")
                        .message("CI trigger processed successfully.")
                        .data(response)
                        .timestamp(LocalDateTime.now())
                        .build());
    }
}
