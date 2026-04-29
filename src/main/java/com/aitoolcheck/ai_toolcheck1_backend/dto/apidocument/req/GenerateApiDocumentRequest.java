package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req;

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
public class GenerateApiDocumentRequest {

    private UUID projectId;
    private Boolean overwriteCurrentVersion;
}