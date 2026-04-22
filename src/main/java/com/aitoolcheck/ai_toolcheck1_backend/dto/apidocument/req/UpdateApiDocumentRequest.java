package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req;

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

public class UpdateApiDocumentRequest {
    private Integer currentVersion;
    private Boolean publishedFlag;
}
