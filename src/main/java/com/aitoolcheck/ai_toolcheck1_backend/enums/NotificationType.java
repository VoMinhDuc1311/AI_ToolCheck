package com.aitoolcheck.ai_toolcheck1_backend.enums;

/**
 * Notification type codes used to categorize persistent notifications.
 * Frontend can use these to render appropriate icons or route to relevant screens.
 */
public enum NotificationType {

    // Source project lifecycle
    SOURCE_UPLOAD_COMPLETED,
    SOURCE_ANALYSIS_COMPLETED,

    // Agent 1 scan
    AGENT1_SCAN_COMPLETED,
    AGENT1_SCAN_FAILED,

    // Metadata cleanup
    METADATA_CLEANUP_COMPLETED,
    METADATA_CLEANUP_FAILED,

    // OpenAPI generation
    OPENAPI_GENERATED,
    OPENAPI_GENERATION_FAILED,

    // Test run
    TEST_RUN_COMPLETED,
    TEST_RUN_FAILED,

    // Project members
    PROJECT_MEMBER_ADDED,
    PROJECT_MEMBER_REMOVED,
    PROJECT_ROLE_UPDATED,

    // General
    INFO
}
