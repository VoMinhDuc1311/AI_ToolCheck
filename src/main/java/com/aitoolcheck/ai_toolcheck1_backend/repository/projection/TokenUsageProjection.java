package com.aitoolcheck.ai_toolcheck1_backend.repository.projection;

public interface TokenUsageProjection {
    Long getTotalInputToken();
    Long getTotalOutputToken();
    Long getTotalToken();
}
