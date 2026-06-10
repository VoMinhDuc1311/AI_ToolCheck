-- V14: Persist BatchRun runtime build strategy option.
-- Defaults to AUTO for backward compatibility with existing batch runs.

ALTER TABLE batch_run
    ADD COLUMN build_strategy VARCHAR(50) NULL;

UPDATE batch_run
SET build_strategy = 'AUTO'
WHERE build_strategy IS NULL;

ALTER TABLE batch_run
    MODIFY COLUMN build_strategy VARCHAR(50) NOT NULL DEFAULT 'AUTO';
