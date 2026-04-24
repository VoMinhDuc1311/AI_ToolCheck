package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration Properties for Google Gemini API
 * Maps properties from ai.gemini section in application.yaml
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.gemini")
public class GeminiProperties {

    /**
     * Google Gemini API Key
     * Sourced from environment variable: AI_GEMINI_API_KEY
     */
    private String apiKey;

    /**
     * Base URL for Google Gemini API
     * Default: https://generativelanguage.googleapis.com/v1beta/models
     */
    private String baseUrl;

    /**
     * Model name to use for API calls
     * Default: gemini-2.0-pro (Latest Pro model)
     */
    private String model;
}
