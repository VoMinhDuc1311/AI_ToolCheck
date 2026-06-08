package com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal;

import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
public class RuntimeStatusSnapshot {
    UUID runtimeId;
    RuntimeStatus status;
    String publicBaseUrl;
    String lastHealthStatus;
    String lastError;
    LocalDateTime updatedAt;
}
