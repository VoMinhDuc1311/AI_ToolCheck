package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataCleanupResult;
import java.util.UUID;

public interface ApiMetadataCleanupService {
    ApiMetadataCleanupResult cleanupProjectApiMetadata(UUID projectId);
}
