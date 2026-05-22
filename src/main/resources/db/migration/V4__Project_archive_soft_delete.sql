-- ============================================================
-- V4: Project Archive / Soft-Delete Lifecycle Columns
-- Adds archive and delete tracking to source_project.
-- Archive = hidden from default list, all child data preserved.
-- Deleted = permanent delete in progress or completed.
-- Both are OWNER/ADMIN-only lifecycle operations.
-- ============================================================

ALTER TABLE source_project
    ADD COLUMN archived_flag  BIT(1)      NOT NULL DEFAULT b'0'  AFTER status,
    ADD COLUMN archived_at    DATETIME(6) NULL                    AFTER archived_flag,
    ADD COLUMN archived_by    BINARY(16)  NULL                    AFTER archived_at,
    ADD COLUMN deleted_flag   BIT(1)      NOT NULL DEFAULT b'0'  AFTER archived_by,
    ADD COLUMN deleted_at     DATETIME(6) NULL                    AFTER deleted_flag,
    ADD COLUMN deleted_by     BINARY(16)  NULL                    AFTER deleted_at;

CREATE INDEX idx_source_project_archived_flag
    ON source_project (archived_flag);

CREATE INDEX idx_source_project_deleted_flag
    ON source_project (deleted_flag);
