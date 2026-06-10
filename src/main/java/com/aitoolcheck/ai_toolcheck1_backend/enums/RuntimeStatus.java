package com.aitoolcheck.ai_toolcheck1_backend.enums;

public enum RuntimeStatus {
    NOT_CREATED,
    BUILD_QUEUED,
    BUILDING,
    BUILD_FAILED,
    /**
     * The host execution environment does not have the required capabilities
     * to build or run this source (e.g. no Docker socket, no JDK).
     * This is an honest status — no build was attempted.
     */
    ENVIRONMENT_UNSUPPORTED,
    STARTING,
    START_FAILED,
    UP,
    UNHEALTHY,
    STOPPING,
    STOPPED
}
