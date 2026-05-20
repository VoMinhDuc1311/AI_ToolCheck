package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.ci.req.CiTriggerRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.ci.res.CiTriggerResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.service.CiTriggerService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class CiTriggerServiceImpl implements CiTriggerService {

    private static final int MAX_RUN_NAME_LENGTH = 150;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final int SHORT_SHA_LENGTH = 8;

    private final TestRunService testRunService;

    @Override
    public CiTriggerResponse trigger(CiTriggerRequest request) {

        validateCiRequest(request);

        CreateTestRunRequest createRequest = buildCreateRequest(request);

        log.info("[CiTrigger] Creating TestRun: projectId={}, baseUrl={}, branch={}, sha={}, triggeredBy={}",
                request.getProjectId(),
                request.getBaseUrl(),
                request.getBranchName(),
                request.getCommitSha(),
                request.getTriggeredBy());

        // Step 1: Create the TestRun (PENDING). ProjectAccessService enforces project permissions.
        TestRunDetailResponse created = testRunService.create(createRequest);

        boolean runImmediately = Boolean.TRUE.equals(request.getRunImmediately());

        if (!runImmediately) {
            log.info("[CiTrigger] TestRun created (no execution): id={}, status={}",
                    created.getId(), created.getRunStatus());
            return buildResponse(created, false, request);
        }

        // Step 2: Execute synchronously. Preserves WebSocket realtime events.
        // Uses existing execute(id) — NOT the async variant — so the HTTP response
        // carries the definitive COMPLETED/FAILED status needed to gate the pipeline.
        log.info("[CiTrigger] Executing TestRun synchronously: id={}", created.getId());
        TestRunDetailResponse executed = testRunService.execute(created.getId());

        log.info("[CiTrigger] TestRun execution complete: id={}, status={}",
                executed.getId(), executed.getRunStatus());

        return buildResponse(executed, true, request);
    }


    private void validateCiRequest(CiTriggerRequest request) {
        boolean hasExplicitIds = request.getTestCaseIds() != null
                && !request.getTestCaseIds().isEmpty();
        boolean includeAll = Boolean.TRUE.equals(request.getIncludeAllActive());

        if (!hasExplicitIds && !includeAll) {
            throw new BadRequestException(
                    "Either testCaseIds must be non-empty or includeAllActive must be true");
        }
    }

    /**
     * Map CiTriggerRequest → CreateTestRunRequest, generating runName and description.
     */
    private CreateTestRunRequest buildCreateRequest(CiTriggerRequest request) {
        String runName = resolveRunName(request);
        String description = buildCiDescription(request);

        return CreateTestRunRequest.builder()
                .projectId(request.getProjectId())
                .runName(runName)
                .description(description)
                .baseUrl(request.getBaseUrl())
                .environmentName(request.getEnvironmentName())
                .executionMode(request.getExecutionMode())
                .testCaseIds(request.getTestCaseIds())
                .includeAllActive(request.getIncludeAllActive())
                .build();
    }


    private String resolveRunName(CiTriggerRequest request) {
        String provided = request.getRunName();
        if (provided != null && !provided.isBlank()) {
            String trimmed = provided.trim();
            return trimmed.length() > MAX_RUN_NAME_LENGTH
                    ? trimmed.substring(0, MAX_RUN_NAME_LENGTH)
                    : trimmed;
        }

        String branch = hasText(request.getBranchName())
                ? request.getBranchName().trim()
                : "pipeline";

        String sha = hasText(request.getCommitSha())
                ? shortSha(request.getCommitSha().trim())
                : "manual";

        String generated = "CI-" + branch + "-" + sha;

        return generated.length() > MAX_RUN_NAME_LENGTH
                ? generated.substring(0, MAX_RUN_NAME_LENGTH)
                : generated;
    }


    private String buildCiDescription(CiTriggerRequest request) {
        StringBuilder sb = new StringBuilder("CI trigger");

        if (hasText(request.getBranchName())) {
            sb.append(" | branch=").append(request.getBranchName().trim());
        }
        if (hasText(request.getCommitSha())) {
            sb.append(" | commit=").append(request.getCommitSha().trim());
        }
        if (hasText(request.getTriggeredBy())) {
            sb.append(" | triggeredBy=").append(request.getTriggeredBy().trim());
        }

        String result = sb.toString();
        return result.length() > MAX_DESCRIPTION_LENGTH
                ? result.substring(0, MAX_DESCRIPTION_LENGTH)
                : result;
    }

    /** Build the slim CiTriggerResponse from a TestRunDetailResponse. */
    private CiTriggerResponse buildResponse(
            TestRunDetailResponse detail,
            boolean executionStarted,
            CiTriggerRequest request) {

        return CiTriggerResponse.builder()
                .testRunId(detail.getId())
                .runCode(detail.getRunCode())
                .runStatus(detail.getRunStatus())
                .totalItems(detail.getTotalItems())
                .executionStarted(executionStarted)
                .branchName(request.getBranchName())
                .commitSha(request.getCommitSha())
                .createdAt(detail.getCreatedAt())
                .build();
    }

    private String shortSha(String sha) {
        return sha.length() > SHORT_SHA_LENGTH ? sha.substring(0, SHORT_SHA_LENGTH) : sha;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
