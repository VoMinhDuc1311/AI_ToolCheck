package com.aitoolcheck.ai_toolcheck1_backend.service;

import java.util.UUID;

public interface ApiMetadataCleanupService {
    void cleanupProjectApiMetadata(UUID projectId);
}
