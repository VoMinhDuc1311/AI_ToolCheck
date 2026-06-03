package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "runtime.auto")
public class RuntimeAutoProperties {

    private boolean enabled = false;
    private String dockerNetwork = "ai-toolcheck-runtime";
    private int internalPort = 8080;
    private int buildTimeoutSeconds = 300;
    private int startupTimeoutSeconds = 120;
    private int maxActiveRuntimes = 5;
}
