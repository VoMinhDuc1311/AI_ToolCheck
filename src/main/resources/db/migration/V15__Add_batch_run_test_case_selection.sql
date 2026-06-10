ALTER TABLE batch_run
    ADD COLUMN IF NOT EXISTS test_case_ids_json TEXT;
