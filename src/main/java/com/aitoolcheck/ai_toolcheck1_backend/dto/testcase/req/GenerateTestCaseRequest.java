package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateTestCaseRequest {

    @NotNull(message = "projectId is required")
    private UUID projectId;

    private UUID apiEndpointId;

    private UUID apiDocumentVersionId;

    private Boolean overwriteExisting;
}