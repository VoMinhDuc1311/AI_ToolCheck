package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO cho endpoint POST /{projectId}/generate-docs-from-source.
 * Trả về kết quả pipeline: analyze → parse (nếu modern) → AI jobs (nếu legacy) → OpenAPI.
 * HTTP 202 nếu aiJobsTriggered > 0 (async), HTTP 200 nếu openApiGenerated = true (sync).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceDocumentationPipelineResponse {

    private UUID projectId;
    private SourceStyle sourceStyle;
    private Boolean parserRecommended;
    private Boolean aiRecommended;
    private Boolean parserExecuted;
    private Integer aiJobsTriggered;
    private List<UUID> aiJobIds;
    private Boolean openApiGenerated;
    private UUID apiDocumentVersionId;
    private String message;
}
