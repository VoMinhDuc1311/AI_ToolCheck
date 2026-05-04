package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class CreateApiDocumentVersionRequest {
    @NotNull(message = "apiDocumentId is required")
    private UUID apiDocumentId;

    private Integer versionNo;

    private String summary;

    private String description;

    private String contentJson;

    private String exampleRequestJson;

    private String exampleResponseJson;

    private String openapiFragmentJson;

    private Boolean aiEnrichedFlag;
}
