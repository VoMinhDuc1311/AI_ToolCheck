-- ============================================================
-- NOTE: This file is FOR REFERENCE ONLY.
-- This project uses Hibernate ddl-auto=update (NOT Flyway).
-- There is NO flyway dependency in pom.xml and no flyway_schema_history table.
-- The scan_batch_id column was added to DB automatically by Hibernate
-- when it read the @Column(name="scan_batch_id") field on AiJobLog.java.
--
-- If Flyway is introduced in the future, this file must be reviewed carefully
-- before being activated as a versioned migration, because:
--   1. The column may already exist (IF NOT EXISTS handles this safely).
--   2. The existing DB must be baselined first (flyway baseline).
-- ============================================================

-- V5: Add scan_batch_id to ai_job_log
-- Purpose: Groups all AI jobs created in a single "generate-docs-from-source" scan run.
-- FE uses this UUID to filter/poll jobs belonging to the same batch.
-- Nullable: existing rows will have NULL (backward compatible).

ALTER TABLE ai_job_log
    ADD COLUMN IF NOT EXISTS scan_batch_id BINARY(16) NULL DEFAULT NULL
        COMMENT 'UUID grouping all jobs from one generate-docs-from-source run';

-- Index for efficient FE polling by batch (SELECT ... WHERE scan_batch_id = ? AND execution_status IN (...))
CREATE INDEX IF NOT EXISTS idx_ai_job_log_scan_batch_id
    ON ai_job_log (scan_batch_id);
