package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class UpdateApiDocumentVersionRequest {
    private Integer version;
    private String summary;
    private String description;
    private String exampleRequestJson;
    private String exampleResponseJson;
    private String openapiFragmentJson;
    private Boolean aiEnrichedFlag;
}
