-- V8: Add defaultTargetBaseUrl to source_project
-- Business purpose:
--   repositoryUrl  = GitHub source code location used ONLY for static analysis (Agent 1).
--   defaultTargetBaseUrl = runtime URL where the analysed app is actually running,
--                          used by TestRun execution (Agent 3).
--   These two fields are intentionally separate and must NEVER be conflated.
--
-- Safe migration: nullable column, no NOT NULL constraint, no impact on existing rows.

ALTER TABLE source_project
    ADD COLUMN default_target_base_url VARCHAR(500) NULL
    COMMENT 'Runtime base URL of the analysed application (e.g. http://52.220.34.212:8081). Distinct from repositoryUrl.';

CREATE INDEX idx_source_project_default_target_base_url
    ON source_project (default_target_base_url(191));
