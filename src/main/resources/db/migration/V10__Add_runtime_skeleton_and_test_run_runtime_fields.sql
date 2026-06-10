-- V10: Runtime skeleton and TestRun runtime metadata.
-- Safe migration: all TestRun additions are nullable; source_runtime is additive only.

CREATE TABLE source_runtime (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    source_version_id BINARY(16) NULL,
    runtime_mode VARCHAR(50) NOT NULL,
    runtime_status VARCHAR(50) NOT NULL,
    runtime_type VARCHAR(50) NULL,
    container_name VARCHAR(255) NULL,
    image_name VARCHAR(255) NULL,
    docker_network VARCHAR(255) NULL,
    internal_base_url VARCHAR(500) NULL,
    public_base_url VARCHAR(500) NULL,
    internal_port INTEGER NULL,
    detected_port INTEGER NULL,
    context_path VARCHAR(255) NULL,
    health_check_path VARCHAR(255) NULL,
    last_health_status VARCHAR(100) NULL,
    last_error TEXT NULL,
    build_started_at DATETIME(6) NULL,
    build_finished_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    stopped_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_source_runtime_project_id ON source_runtime (project_id);
CREATE INDEX idx_source_runtime_source_version_id ON source_runtime (source_version_id);
CREATE INDEX idx_source_runtime_runtime_status ON source_runtime (runtime_status);
CREATE INDEX idx_source_runtime_project_source_version ON source_runtime (project_id, source_version_id);

ALTER TABLE source_runtime
    ADD CONSTRAINT fk_source_runtime_project
        FOREIGN KEY (project_id) REFERENCES source_project (id);

ALTER TABLE source_runtime
    ADD CONSTRAINT fk_source_runtime_source_version
        FOREIGN KEY (source_version_id) REFERENCES source_upload_version (id);

ALTER TABLE test_run
    ADD COLUMN runtime_mode VARCHAR(50) NULL,
    ADD COLUMN source_runtime_id BINARY(16) NULL,
    ADD COLUMN target_base_url_used VARCHAR(500) NULL,
    ADD COLUMN runtime_status_at_start VARCHAR(50) NULL,
    ADD COLUMN preflight_status VARCHAR(50) NULL,
    ADD COLUMN preflight_summary TEXT NULL;

CREATE INDEX idx_test_run_runtime_mode ON test_run (runtime_mode);
CREATE INDEX idx_test_run_source_runtime_id ON test_run (source_runtime_id);

ALTER TABLE test_run
    ADD CONSTRAINT fk_test_run_source_runtime
        FOREIGN KEY (source_runtime_id) REFERENCES source_runtime (id);
