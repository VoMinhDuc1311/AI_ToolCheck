package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor

public class ApiDocumentVersionDetailResponse {
    private UUID id;

    private UUID apiDocumentId;

    private UUID projectId;

    private Integer versionNo;

    private String summary;

    private String description;

    private String contentJson;

    private String exampleRequestJson;

    private String exampleResponseJson;

    private String openapiFragmentJson;

    private Boolean aiEnrichedFlag;

    private Integer contentLength;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
