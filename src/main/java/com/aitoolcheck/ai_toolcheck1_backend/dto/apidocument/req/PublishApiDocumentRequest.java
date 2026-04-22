package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublishApiDocumentRequest {

    private UUID apiDocumentId;
    private Integer version;
}
