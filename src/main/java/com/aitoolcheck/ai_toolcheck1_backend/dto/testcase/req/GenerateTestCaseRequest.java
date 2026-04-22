package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;
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
public class GenerateTestCaseRequest {

    private UUID projectId;
    private UUID apiEndpointId;
    private UUID apiDocumentVersionId;
    private Boolean overwriteExisting;
}