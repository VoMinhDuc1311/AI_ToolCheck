package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedTestCaseAiPayload {
    private UUID projectId;
    private String projectName;
    private UUID testRunId;
    private UUID testRunItemId;
    private UUID testResultId;
    private UUID testCaseId;
    private String testCaseName;
    
    private FailedEndpointSnapshotDto endpoint;
    private FailedTestCaseRequestSnapshotDto request;
    private FailedTestCaseExpectedDto expected;
    private FailedTestCaseActualDto actual;
}
