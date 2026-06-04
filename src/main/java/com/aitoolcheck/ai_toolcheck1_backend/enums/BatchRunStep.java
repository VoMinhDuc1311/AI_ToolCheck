package com.aitoolcheck.ai_toolcheck1_backend.enums;

public enum BatchRunStep {
    GENERATE_OPENAPI,
    GENERATE_TEST_CASES,
    START_RUNTIME,
    CREATE_TEST_RUN,
    EXECUTE_TEST_RUN,
    STOP_RUNTIME,
    DONE
}
