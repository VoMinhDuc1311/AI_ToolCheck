CREATE TABLE batch_run (
    id BINARY(16) NOT NULL,
    name VARCHAR(150) NOT NULL,
    status VARCHAR(50) NOT NULL,
    total_items INTEGER NOT NULL,
    success_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    skipped_count INTEGER NOT NULL,
    running_count INTEGER NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_by BINARY(16) NULL,
    error_message TEXT NULL,
    generate_open_api BIT NOT NULL,
    generate_test_cases BIT NOT NULL,
    start_runtime BIT NOT NULL,
    execute_test_run BIT NOT NULL,
    stop_runtime_after_run BIT NOT NULL,
    max_concurrency INTEGER NOT NULL,
    max_retries INTEGER NOT NULL,
    execution_mode VARCHAR(50) NOT NULL,
    external_base_url VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_batch_run_status ON batch_run (status);
CREATE INDEX idx_batch_run_created_at ON batch_run (created_at);

CREATE TABLE batch_run_item (
    id BINARY(16) NOT NULL,
    batch_run_id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    current_step VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    retry_count INTEGER NOT NULL,
    max_retries INTEGER NOT NULL,
    error_message TEXT NULL,
    api_document_version_id BINARY(16) NULL,
    test_run_id BINARY(16) NULL,
    runtime_id BINARY(16) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_batch_run_item_batch_run_id ON batch_run_item (batch_run_id);
CREATE INDEX idx_batch_run_item_project_id ON batch_run_item (project_id);
CREATE INDEX idx_batch_run_item_status ON batch_run_item (status);

ALTER TABLE batch_run_item
    ADD CONSTRAINT fk_batch_run_item_batch_run
        FOREIGN KEY (batch_run_id) REFERENCES batch_run (id);

ALTER TABLE batch_run_item
    ADD CONSTRAINT fk_batch_run_item_project
        FOREIGN KEY (project_id) REFERENCES source_project (id);

ALTER TABLE batch_run_item
    ADD CONSTRAINT fk_batch_run_item_api_document_version
        FOREIGN KEY (api_document_version_id) REFERENCES api_document_version (id);

ALTER TABLE batch_run_item
    ADD CONSTRAINT fk_batch_run_item_test_run
        FOREIGN KEY (test_run_id) REFERENCES test_run (id);

ALTER TABLE batch_run_item
    ADD CONSTRAINT fk_batch_run_item_runtime
        FOREIGN KEY (runtime_id) REFERENCES source_runtime (id);
