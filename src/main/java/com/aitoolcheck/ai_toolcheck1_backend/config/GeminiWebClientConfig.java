package com.aitoolcheck.ai_toolcheck1_backend.config;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.util.concurrent.TimeUnit;

/**
 * Configuration for Google Gemini API Integrations
 *
 * Configures:
 * 1. A non-blocking WebClient with custom timeouts, headers, pooling, and 16MB
 * buffer.
 * 2. A global ObjectMapper configured to safely parse AI-generated JSON.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class GeminiWebClientConfig {

        private final GeminiProperties geminiProperties;

        /**
         * Creates a WebClient bean configured for Google Gemini API calls
         *
         * @return Configured WebClient bean
         */
        @Bean(name = "geminiWebClient")
        public WebClient geminiWebClient() {
                log.info("[GeminiWebClientConfig] Initializing GeminiWebClient — baseUrl: {}, model: {} (apiKey: {})",
                        geminiProperties.getBaseUrl(),
                        geminiProperties.getModel(),
                        geminiProperties.getApiKey() != null && !geminiProperties.getApiKey().isBlank()
                                ? "***SET***" : "***MISSING***");

                // Configure connection provider with pooling for performance optimization
                ConnectionProvider connectionProvider = ConnectionProvider.builder("gemini-pool")
                                .maxConnections(100)
                                .maxIdleTime(java.time.Duration.ofSeconds(60))
                                .maxLifeTime(java.time.Duration.ofMinutes(30))
                                .pendingAcquireTimeout(java.time.Duration.ofSeconds(45))
                                .evictInBackground(java.time.Duration.ofSeconds(120))
                                .build();

                // Configure HttpClient with timeout handlers
                HttpClient httpClient = HttpClient.create(connectionProvider)
                                // Connection timeout: 10 seconds
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                                // Read timeout: 60 seconds (AI generation can take time)
                                .responseTimeout(java.time.Duration.ofSeconds(60))
                                // Add read/write timeout handlers at TCP layer
                                .doOnConnected(connection -> {
                                        connection.addHandlerLast(new ReadTimeoutHandler(60, TimeUnit.SECONDS));
                                        connection.addHandlerLast(new WriteTimeoutHandler(60, TimeUnit.SECONDS));
                                });

                // Configure WebClient with custom HttpClient and exchange strategies
                return WebClient.builder()
                                .baseUrl(geminiProperties.getBaseUrl())
                                .clientConnector(new ReactorClientHttpConnector(httpClient))
                                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .defaultHeader("x-goog-api-key", geminiProperties.getApiKey())
                                // Optimize for large payloads (e.g., long text generation)
                                .exchangeStrategies(ExchangeStrategies.builder()
                                                .codecs(configurer -> configurer.defaultCodecs()
                                                                .maxInMemorySize(1024 * 1024 * 16)) // 16MB buffer
                                                .build())
                                .build();
        }

        /**
         * Creates a globally available ObjectMapper bean.
         * Configured specifically for parsing AI JSON safely without crashing the app.
         *
         * @return Configured ObjectMapper bean
         */
        @Bean
        public ObjectMapper objectMapper() {
                ObjectMapper mapper = new ObjectMapper();

                // CRITICAL FOR AI: Ignore extra fields hallucinated by the model
                // If the AI returns {"path": "/api", "unexpected_field": "xyz"}, it won't
                // crash.
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

                return mapper;
        }
}