package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /**
     * Allowed origin patterns for the React/Vite frontend.
     * Supports wildcard patterns (e.g. https://*.vercel.app) via
     * CorsConfiguration.setAllowedOriginPatterns(), which is compatible
     * with allowCredentials(true) — unlike setAllowedOrigins("*").
     */
    private List<String> allowedOriginPatterns = List.of();

    private List<String> allowedMethods = List.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
    );

    private List<String> allowedHeaders = List.of(
            "Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"
    );
}
