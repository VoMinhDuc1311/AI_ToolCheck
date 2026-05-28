-- V7: Add optional GitHub repository metadata columns to source_project
-- Business goal: each SourceProject can optionally store a GitHub repository URL and branch.
-- Safe migration: nullable columns, no NOT NULL constraints, no impact on existing rows.

ALTER TABLE source_project
    ADD COLUMN repository_url    VARCHAR(500) NULL,
    ADD COLUMN repository_branch VARCHAR(120) NULL;
