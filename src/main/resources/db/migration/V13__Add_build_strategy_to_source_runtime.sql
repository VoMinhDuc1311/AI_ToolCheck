-- V13: Add build strategy metadata fields to source_runtime.
-- All new columns are nullable for backward compatibility with existing rows.
-- build_strategy_requested: the strategy requested by the caller (AUTO, UPLOADED_DOCKERFILE_ONLY, etc.)
-- build_strategy_used:      the strategy that was actually executed (may differ from AUTO_WITH_FALLBACK path)
-- dockerfile_source:        UPLOADED or GENERATED — which Dockerfile was actually used
-- fallback_reason:          set when AUTO_WITH_FALLBACK fell back to generated Dockerfile

ALTER TABLE source_runtime
    ADD COLUMN build_strategy_requested VARCHAR(50)  NULL,
    ADD COLUMN build_strategy_used      VARCHAR(50)  NULL,
    ADD COLUMN dockerfile_source        VARCHAR(20)  NULL,
    ADD COLUMN fallback_reason          TEXT         NULL;
