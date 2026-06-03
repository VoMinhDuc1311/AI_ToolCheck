package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "test-run.stale")
public class TestRunStaleProperties {

    private boolean enabled = true;
    private long pendingTimeoutMinutes = 30;
    private long runningTimeoutMinutes = 120;
}
