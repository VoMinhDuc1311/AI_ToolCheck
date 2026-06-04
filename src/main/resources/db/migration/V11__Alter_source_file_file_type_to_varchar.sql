-- V11: Alter source_file.file_type from MySQL ENUM to VARCHAR(50).
--
-- Root cause: The file_type column was created as a MySQL ENUM by Hibernate
-- (MySQLDialect maps @Enumerated(EnumType.STRING) to ENUM type) when the table
-- was first initialised. Phase 0 added three new Java enum values
-- (BUILD, APP_CONFIG, SCRIPT) that are not present in the existing ENUM column
-- definition, causing MySQL to raise "Data truncated for column 'file_type'"
-- on every INSERT that uses a new value.
--
-- Fix: Migrate to VARCHAR(50) NOT NULL.
-- VARCHAR is preferred over adding values to the ENUM because:
--   1. No repeated ALTER TABLE … MODIFY COLUMN ENUM needed for future values.
--   2. MySQL ALTER ENUM requires a full table rebuild; VARCHAR avoids that cost.
--   3. @Enumerated(EnumType.STRING) persists the Java enum name() which fits
--      comfortably in VARCHAR(50) (longest current value: EXCEPTION_HANDLER = 17 chars).
--
-- This migration is safe to run on a live production table:
--   * Existing rows keep their current string values unchanged.
--   * NOT NULL is preserved from the original column constraint.

ALTER TABLE source_file
    MODIFY COLUMN file_type VARCHAR(50) NOT NULL;
