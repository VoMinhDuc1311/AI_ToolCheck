package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateTestCaseRequest {

    private UUID projectId;
    private UUID apiEndpointId;
    private UUID apiDocumentVersionId;
    private Boolean overwriteExisting;
}