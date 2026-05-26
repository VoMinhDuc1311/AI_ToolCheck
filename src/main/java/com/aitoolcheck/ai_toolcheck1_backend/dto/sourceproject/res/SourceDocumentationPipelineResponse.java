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
 *
 * <p>New optional fields (nullable, backward-compatible):
 * <ul>
 *   <li>{@code scanBatchId} — UUID identifying this scan batch for FE monitoring</li>
 *   <li>{@code candidateFiles} — number of files selected for AI inference</li>
 *   <li>{@code skippedFiles} — number of helper/non-entrypoint files skipped</li>
 *   <li>{@code skippedReasons} — per-file skip reasons (debug aid)</li>
 *   <li>{@code providerPlan} — label describing the AI provider strategy used</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceDocumentationPipelineResponse {

    // ── Existing fields (unchanged) ──────────────────────────────────────────
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

    // ── New optional fields (backward-compatible, all nullable) ──────────────

    /**
     * UUID generated for this specific scan run.
     * All AI jobs created in this run share this batch ID.
     * FE can use this to filter jobs belonging to the current scan only.
     * NOTE: Stored in memory only; DB migration for scan_batch_id is a separate step.
     */
    private UUID scanBatchId;

    /** Number of files classified as entrypoint candidates (AI job created for each). */
    private Integer candidateFiles;

    /** Number of files skipped because they were classified as helper/non-entrypoint. */
    private Integer skippedFiles;

    /**
     * Abbreviated skip reason per file path.
     * Format: "FileName.java: SKIP reason"
     * Useful for debugging classifier decisions in the UI.
     */
    private List<String> skippedReasons;

    /**
     * Human-readable label for the AI provider strategy.
     * Example: "GEMINI_PRIMARY_OLLAMA_FALLBACK"
     */
    private String providerPlan;
}

