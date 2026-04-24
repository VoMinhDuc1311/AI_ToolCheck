package com.aitoolcheck.ai_toolcheck1_backend.config;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
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
 * WebClient Configuration for Google Gemini API
 *
 * Configures a non-blocking WebClient with:
 * - Custom timeout settings (Connection: 10s, Read/Write: 60s)
 * - Default headers (Content-Type, API Key)
 * - Connection pooling (Max 100 connections)
 * - Increased memory buffer for large AI responses (16MB)
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
        log.info("Initializing GeminiWebClient with base URL: {}", geminiProperties.getBaseUrl());

        // Configure connection provider with pooling for performance optimization
        ConnectionProvider connectionProvider = ConnectionProvider.builder("gemini-pool")
                .maxConnections(100)
                .maxIdleTime(java.time.Duration.ofSeconds(60))
                .maxLifeTime(java.time.Duration.ofMinutes(30))
                .pendingAcquireTimeout(java.time.Duration.ofSeconds(45))
                .evictInBackground(java.time.Duration.ofSeconds(120))
                .build();

        // Configure HttpClient with timeout handlers
        // Removed the explicit .secure() block as Netty handles standard HTTPS automatically
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
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024 * 16)) // 16MB buffer
                        .build())
                .build();
    }
}