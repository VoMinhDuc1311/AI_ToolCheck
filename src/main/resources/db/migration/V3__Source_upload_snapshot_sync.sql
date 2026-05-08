CREATE TABLE IF NOT EXISTS source_upload_version (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    version_no INT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    total_java_files_found INT NULL,
    saved_files INT NULL,
    ignored_files INT NULL,
    added_files INT NULL,
    updated_files INT NULL,
    unchanged_files INT NULL,
    deleted_files INT NULL,
    status VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_source_upload_version_project
        FOREIGN KEY (project_id) REFERENCES source_project (id),
    CONSTRAINT uk_source_upload_version_project_no
        UNIQUE (project_id, version_no)
);

ALTER TABLE source_file
    ADD COLUMN active_flag BIT(1) NOT NULL DEFAULT b'1',
    ADD COLUMN deleted_flag BIT(1) NOT NULL DEFAULT b'0',
    ADD COLUMN upload_version_id BINARY(16) NULL,
    ADD COLUMN last_seen_upload_version_id BINARY(16) NULL;

ALTER TABLE source_file
    ADD CONSTRAINT fk_source_file_upload_version
        FOREIGN KEY (upload_version_id) REFERENCES source_upload_version (id),
    ADD CONSTRAINT fk_source_file_last_seen_upload_version
        FOREIGN KEY (last_seen_upload_version_id) REFERENCES source_upload_version (id);

ALTER TABLE api_endpoint
    ADD COLUMN active_flag BIT(1) NOT NULL DEFAULT b'1',
    ADD COLUMN stale_flag BIT(1) NOT NULL DEFAULT b'0',
    ADD COLUMN stable_key VARCHAR(700) NULL,
    ADD COLUMN source_upload_version_id BINARY(16) NULL;

ALTER TABLE api_endpoint
    ADD CONSTRAINT fk_api_endpoint_source_upload_version
        FOREIGN KEY (source_upload_version_id) REFERENCES source_upload_version (id);

ALTER TABLE api_document
    ADD COLUMN stale_flag BIT(1) NOT NULL DEFAULT b'0';

ALTER TABLE source_analysis_result
    ADD COLUMN current_flag BIT(1) NOT NULL DEFAULT b'1',
    ADD COLUMN source_upload_version_id BINARY(16) NULL;

ALTER TABLE source_analysis_result
    ADD CONSTRAINT fk_source_analysis_upload_version
        FOREIGN KEY (source_upload_version_id) REFERENCES source_upload_version (id);

CREATE INDEX idx_source_upload_version_project
    ON source_upload_version (project_id);

CREATE INDEX idx_source_file_active_project
    ON source_file (project_id, active_flag, deleted_flag);

CREATE INDEX idx_api_endpoint_stable_key
    ON api_endpoint (project_id, http_method, endpoint_path);
