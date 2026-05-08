-- AI ToolCheck Project Access Control manual migration for MySQL.
--
-- This repository currently has db/migration scripts but no Flyway/Liquibase
-- dependency, so operators must run this script manually or translate it into
-- their managed migration system.
--
-- Before running the backfill, verify the chosen admin user exists:
--   SELECT id, email, role FROM app_user WHERE email = 'admin@aitoolcheck.local';
--
-- If your production admin email is different, replace the email below before
-- running the UPDATE. Do not hardcode an admin UUID in application source.
--
-- The script may not be safely re-runnable on all MySQL versions due to duplicate indexes/constraints.
-- Keep owner_user_id nullable in this feature.

-- MySQL 8.0.29+ supports ADD COLUMN IF NOT EXISTS. On older MySQL versions,
-- inspect INFORMATION_SCHEMA.COLUMNS before running these ALTER statements.
ALTER TABLE source_project
    ADD COLUMN IF NOT EXISTS owner_user_id BINARY(16) NULL;

ALTER TABLE source_project
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(50) NOT NULL DEFAULT 'PRIVATE';

CREATE TABLE IF NOT EXISTS project_member (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    role VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_member_project_user UNIQUE (project_id, user_id),
    CONSTRAINT fk_project_member_project
        FOREIGN KEY (project_id) REFERENCES source_project (id),
    CONSTRAINT fk_project_member_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE INDEX idx_project_member_project_id
    ON project_member (project_id);

CREATE INDEX idx_project_member_user_id
    ON project_member (user_id);

CREATE INDEX idx_project_member_role
    ON project_member (role);

CREATE INDEX idx_source_project_owner_user_id
    ON source_project (owner_user_id);

CREATE INDEX idx_source_project_visibility
    ON source_project (visibility);

-- Backfill existing projects to a chosen admin account.
-- Replace admin@aitoolcheck.local if your environment uses another seed/admin email.
UPDATE source_project sp
JOIN app_user au ON au.email = 'admin@aitoolcheck.local' AND au.role = 'ADMIN'
SET sp.owner_user_id = au.id
WHERE sp.owner_user_id IS NULL;

-- Add the owner foreign key after backfill column creation.
-- If the constraint already exists, skip this statement manually.
ALTER TABLE source_project
    ADD CONSTRAINT fk_source_project_owner_user
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id);

-- Verify before optional hardening:
--   SELECT COUNT(*) FROM source_project WHERE owner_user_id IS NULL;
--
-- Optional later hardening after every row has an owner:
--   ALTER TABLE source_project MODIFY owner_user_id BINARY(16) NOT NULL;
