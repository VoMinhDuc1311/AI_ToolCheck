UPDATE source_project
SET archived_flag = b'0'
WHERE archived_flag IS NULL;

ALTER TABLE source_project
    MODIFY COLUMN archived_flag BIT(1) NOT NULL DEFAULT b'0';
