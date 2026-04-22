package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req;

import lombok.*;

import java.util.UUID;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateApiDocumentRequest {

    private UUID apiEndpointId;
    private Boolean overwriteCurrentVersion;
}