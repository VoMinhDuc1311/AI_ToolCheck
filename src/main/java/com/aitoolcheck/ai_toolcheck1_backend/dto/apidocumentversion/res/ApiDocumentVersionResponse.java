package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res;

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
@NoArgsConstructor
@AllArgsConstructor



public class ApiDocumentVersionResponse {
    private UUID id;

    private UUID apiDocumentId;

    private UUID projectId;

    private Integer versionNo;

    private String summary;

    private String description;

    private Boolean aiEnrichedFlag;

    private Integer contentLength;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
