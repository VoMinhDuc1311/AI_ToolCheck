package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApiDocumentResponse {
    private UUID id;
    private UUID projectId;
    private String documentName;
    private String documentType;
    private Integer currentVersionNo;
    private Boolean publishedFlag;
    private LocalDateTime createdAt;
}
