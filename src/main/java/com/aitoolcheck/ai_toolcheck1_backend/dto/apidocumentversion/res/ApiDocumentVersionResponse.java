package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApiDocumentVersionResponse {

    private UUID id;

    private UUID apiDocumentId;

    private UUID projectId;

    private Integer versionNo;

    private String summary;

    private String description;

    private Boolean aiEnrichedFlag;

    private Integer contentLength;

    /**
     * Returned with timezone offset, example:
     * 2026-05-26T08:26:38+07:00
     */
    private OffsetDateTime createdAt;

    /**
     * Returned with timezone offset, example:
     * 2026-05-26T08:26:38+07:00
     */
    private OffsetDateTime updatedAt;
}