package com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiGenerateResponse {

    private UUID projectId;
    private UUID apiDocumentId;
    private UUID apiDocumentVersionId;
    private Integer versionNo;
    private Integer totalEndpoints;
    private Integer totalSchemas;
    private Boolean publishedFlag;
    private String summary;
}
