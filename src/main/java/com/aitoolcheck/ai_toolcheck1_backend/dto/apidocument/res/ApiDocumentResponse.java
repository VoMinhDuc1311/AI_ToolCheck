package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.DocumentType;
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
    private DocumentType documentType;
    private Integer currentVersionNo;
    private Boolean publishedFlag;
    private Boolean staleFlag;
    private LocalDateTime createdAt;
}
