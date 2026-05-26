-- V5: Add scan_batch_id to ai_job_log
-- Purpose: Groups all AI jobs created in a single "generate-docs-from-source" scan run.
-- FE uses this UUID to filter/poll jobs belonging to the same batch.
-- Nullable: existing rows will have NULL (backward compatible).

ALTER TABLE ai_job_log
    ADD COLUMN IF NOT EXISTS scan_batch_id CHAR(36) NULL DEFAULT NULL
        COMMENT 'UUID grouping all jobs from one generate-docs-from-source run';

-- Index for efficient FE polling by batch (SELECT ... WHERE scan_batch_id = ? AND execution_status IN (...))
CREATE INDEX IF NOT EXISTS idx_ai_job_log_scan_batch_id
    ON ai_job_log (scan_batch_id);
