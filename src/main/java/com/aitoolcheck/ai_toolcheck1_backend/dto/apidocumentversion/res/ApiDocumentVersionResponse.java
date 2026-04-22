package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res;

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



public class ApiDocumentVersionResponse {
    private UUID id;
    private UUID apiDocumentId;
    private Integer version;
    private String summary;
    private Boolean aiEnrichedFlag;
}
