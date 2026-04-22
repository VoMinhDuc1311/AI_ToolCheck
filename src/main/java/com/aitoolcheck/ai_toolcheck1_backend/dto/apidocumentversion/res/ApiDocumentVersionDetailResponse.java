package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor

public class ApiDocumentVersionDetailResponse {
    private UUID id;
    private UUID apiDocumentId;
    private Integer version;
    private String summary;
    private String description;
    private String exampleRequestJson;
    private String exampleResponseJson;
    private String openapiFragmentJson;
    private Boolean aiEnrichedFlag;
}
