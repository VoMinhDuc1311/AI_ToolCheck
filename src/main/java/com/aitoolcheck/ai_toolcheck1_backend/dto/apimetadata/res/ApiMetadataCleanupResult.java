package com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiMetadataCleanupResult {
    private UUID projectId;
    private int activeBefore;
    private int fallbackMarkedStale;
    private int duplicatesMarkedStale;
    private int activeAfter;
}
