package com.aitoolcheck.ai_toolcheck1_backend.config;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.CorsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CORS configuration logic in SecurityConfig.
 *
 * These tests verify the CorsConfiguration built from CorsProperties directly,
 * without loading a Spring context (no DB, no RabbitMQ, no Ollama needed).
 *
 * Strategy: call CorsConfiguration.checkOrigin() (stable across Spring versions)
 * for origin pattern assertions, and inspect configuration state (getAllowedMethods,
 * getAllowedHeaders) for method/header assertions. This avoids binding to
 * internal check-method signatures that changed in Spring 6→7.
 *
 * The tests mirror the four manual curl verification commands in the deployment
 * checklist.
 */
@DisplayName("CORS Configuration Unit Tests")
class CorsConfigurationTest {

    private CorsConfiguration config;

    @BeforeEach
    void setUp() {
        CorsProperties props = new CorsProperties();
        props.setAllowedOriginPatterns(List.of(
                "http://localhost:5173",
                "http://localhost:4173",
                "http://localhost:3000",
                "http://localhost:5500",
                "http://localhost:63342",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:4173",
                "http://127.0.0.1:3000",
                "http://127.0.0.1:5500",
                "http://127.0.0.1:63342",
                "https://*.vercel.app"
        ));
        props.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        props.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));

        config = new CorsConfiguration();
        config.setAllowedOriginPatterns(props.getAllowedOriginPatterns());
        config.setAllowedMethods(props.getAllowedMethods());
        config.setAllowedHeaders(props.getAllowedHeaders());
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
    }

    // -------------------------------------------------------------------------
    // 1. Local dev origin (http://localhost:5173) — OPTIONS GET preflight
    //    Mirrors: curl -X OPTIONS -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: GET"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OPTIONS preflight from localhost:5173 with GET is accepted")
    void localDevOriginGetPreflightIsAccepted() {
        String resolvedOrigin = config.checkOrigin("http://localhost:5173");
        assertThat(resolvedOrigin)
                .as("localhost:5173 must be an allowed origin")
                .isNotNull()
                .isEqualTo("http://localhost:5173");

        assertThat(config.getAllowedMethods())
                .as("GET must be in allowed methods")
                .contains("GET");
    }

    // -------------------------------------------------------------------------
    // 2. Vercel production origin — OPTIONS GET preflight
    //    Mirrors: curl -X OPTIONS -H "Origin: https://example.vercel.app" -H "Access-Control-Request-Method: GET"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OPTIONS preflight from https://example.vercel.app with GET is accepted")
    void vercelOriginGetPreflightIsAccepted() {
        String resolvedOrigin = config.checkOrigin("https://example.vercel.app");
        assertThat(resolvedOrigin)
                .as("https://example.vercel.app must match the https://*.vercel.app pattern")
                .isNotNull()
                .isEqualTo("https://example.vercel.app");

        assertThat(config.getAllowedMethods())
                .as("GET must be in allowed methods")
                .contains("GET");
    }

    // -------------------------------------------------------------------------
    // 3. Vercel preview origin — OPTIONS POST preflight with content-type + authorization
    //    Mirrors: curl -X OPTIONS -H "Origin: https://example.vercel.app"
    //             -H "Access-Control-Request-Method: POST"
    //             -H "Access-Control-Request-Headers: content-type,authorization"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OPTIONS preflight from Vercel with POST and content-type,authorization headers is accepted")
    void vercelOriginPostPreflightWithSensitiveHeadersIsAccepted() {
        // A Vercel preview URL (dynamic subdomain)
        String resolvedOrigin = config.checkOrigin("https://my-app-git-feature-xyz.vercel.app");
        assertThat(resolvedOrigin)
                .as("A Vercel preview subdomain must match https://*.vercel.app")
                .isNotNull();

        assertThat(config.getAllowedMethods())
                .as("POST must be in allowed methods")
                .contains("POST");

        List<String> allowedHeaders = config.getAllowedHeaders();
        assertThat(allowedHeaders)
                .as("content-type must be listed as an allowed header")
                .anySatisfy(h -> assertThat(h).isEqualToIgnoringCase("Content-Type"));
        assertThat(allowedHeaders)
                .as("authorization must be listed as an allowed header")
                .anySatisfy(h -> assertThat(h).isEqualToIgnoringCase("Authorization"));
    }

    // -------------------------------------------------------------------------
    // 4. Disallowed origin must NOT be granted
    //    Verifies the policy is strict: non-listed origins are rejected
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OPTIONS preflight from https://evil.example.com is rejected")
    void disallowedOriginIsRejected() {
        String resolvedOrigin = config.checkOrigin("https://evil.example.com");
        assertThat(resolvedOrigin)
                .as("An unknown origin must not be allowed")
                .isNull();
    }

    // -------------------------------------------------------------------------
    // 5. Vite preview origin (http://localhost:4173) — must be included
    //    Mirrors: curl -X OPTIONS -H "Origin: http://localhost:4173" -H "Access-Control-Request-Method: GET"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OPTIONS preflight from localhost:4173 (Vite preview) is accepted")
    void localVitePreviewOriginIsAccepted() {
        String resolvedOrigin = config.checkOrigin("http://localhost:4173");
        assertThat(resolvedOrigin)
                .as("localhost:4173 (Vite local preview) must be allowed")
                .isNotNull()
                .isEqualTo("http://localhost:4173");
    }

    // -------------------------------------------------------------------------
    // 6. allowCredentials(true) is preserved and compatible with named patterns
    //    The CORS spec forbids literal "*" with credentials, but named patterns
    //    (including wildcards like *.vercel.app) are each matched individually
    //    and are spec-compliant.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("allowCredentials is true and compatible with named origin patterns")
    void allowCredentialsTrueIsCompatibleWithNamedPatterns() {
        assertThat(config.getAllowCredentials())
                .as("allowCredentials must be true (JWT authorization header flow)")
                .isTrue();

        // Named patterns are compatible — Spring resolves *.vercel.app
        // to the exact requested origin in the response header
        String resolvedOrigin = config.checkOrigin("https://ai-toolcheck.vercel.app");
        assertThat(resolvedOrigin)
                .as("Named patterns are compatible with allowCredentials(true)")
                .isNotNull();
    }
}
