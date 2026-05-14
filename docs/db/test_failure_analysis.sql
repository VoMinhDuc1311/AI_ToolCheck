CREATE TABLE IF NOT EXISTS test_failure_analysis (
    id BINARY(16) NOT NULL,
    test_result_id BINARY(16) NOT NULL,
    ai_job_log_id BINARY(16) NULL,
    model_name VARCHAR(100),
    failure_type VARCHAR(100),
    summary VARCHAR(1000),
    root_cause TEXT,
    expected_behavior TEXT,
    actual_behavior TEXT,
    is_likely_backend_bug BOOLEAN,
    is_likely_test_case_bug BOOLEAN,
    suggested_fixes_json TEXT,
    recommended_next_action TEXT,
    confidence DOUBLE,
    priority VARCHAR(50),
    raw_ai_response LONGTEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_test_failure_analysis_test_result
        FOREIGN KEY (test_result_id) REFERENCES test_result (id),
    CONSTRAINT fk_test_failure_analysis_ai_job_log
        FOREIGN KEY (ai_job_log_id) REFERENCES ai_job_log (id)
);

CREATE INDEX idx_test_failure_analysis_test_result_id
    ON test_failure_analysis (test_result_id);

CREATE INDEX idx_test_failure_analysis_ai_job_log_id
    ON test_failure_analysis (ai_job_log_id);
