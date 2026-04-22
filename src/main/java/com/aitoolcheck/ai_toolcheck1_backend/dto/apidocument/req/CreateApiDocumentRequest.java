package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req;

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

public class CreateApiDocumentRequest {
    private UUID apiEndpointId;
    private Integer currentVersion;
    private Boolean publishedFlag;
}
