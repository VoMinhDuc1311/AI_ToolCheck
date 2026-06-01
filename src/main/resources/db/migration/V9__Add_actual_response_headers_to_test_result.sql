-- V9: Add actual_response_headers_json to test_result
-- Business purpose:
--   Stores HTTP response headers captured during TestRun execution.
--   Required to support HEADER assertion type in RuleEngineServiceImpl.
--   Stored as JSON text: e.g. {"Content-Type":"application/json","X-Request-Id":"abc123"}
--
-- Safe migration: nullable TEXT column, no impact on existing rows.

ALTER TABLE test_result
    ADD COLUMN actual_response_headers_json TEXT NULL
    COMMENT 'HTTP response headers captured at execution time, stored as JSON object string.';
