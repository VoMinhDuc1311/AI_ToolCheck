package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.optimization")
public class AiOptimizationProperties {

    private boolean enabled = true;
    private int maxPromptChars = 25000;
    private int maxResponseCharsToStore = 30000;
    private boolean truncateLargePayload = true;
    private boolean cacheEnabled = true;
    private int cacheTtlMinutes = 1440;
    private boolean retryEnabled = true;
    private int maxRetries = 1;
    private long retryDelayMs = 1000;

    private Batching batching = new Batching();
    private Router router = new Router();

    @Data
    public static class Batching {
        private boolean enabled = true;
        private int defaultMaxItemsPerBatch = 5;
        private int maxItemsPerBatch = 10;
        private int maxCharsPerBatch = 20000;
    }

    @Data
    public static class Router {
        private boolean fallbackToGemini = true;
        private boolean failFastLocalProvider = true;
        private int providerHealthCacheSeconds = 30;
    }
}
