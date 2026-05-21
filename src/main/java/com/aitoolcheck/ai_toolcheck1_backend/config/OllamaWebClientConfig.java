package com.aitoolcheck.ai_toolcheck1_backend.config;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for Ollama Local LLM WebClient.
 *
 * <p>Creates a dedicated {@link WebClient} bean for communicating with
 * the local Ollama server. Timeout values are driven by {@link OllamaProperties}
 * to support both lightweight (7B) and heavyweight (30B) models.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class OllamaWebClientConfig {

    private final OllamaProperties ollamaProperties;

    /**
     * Creates a WebClient bean configured for Ollama API calls.
     * Uses a dedicated connection pool named "ollama-pool" to avoid
     * interfering with the Gemini connection pool.
     *
     * @return Configured WebClient bean for Ollama
     */
    @Bean(name = "ollamaWebClient")
    public WebClient ollamaWebClient() {
        log.info("[OllamaWebClientConfig] Initializing OllamaWebClient — baseUrl: {}, primaryModel: {}, embedModel: {}",
                ollamaProperties.getBaseUrl(),
                ollamaProperties.getPrimaryModel(),
                ollamaProperties.getEmbedModel());

        ConnectionProvider connectionProvider = ConnectionProvider.builder("ollama-pool")
                .maxConnections(20)
                .maxIdleTime(Duration.ofSeconds(60))
                .maxLifeTime(Duration.ofMinutes(30))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                // Connection timeout: configurable, default 10s
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        ollamaProperties.getConnectTimeoutSeconds() * 1000)
                // Response timeout: configurable, default 120s for large models
                .responseTimeout(Duration.ofSeconds(ollamaProperties.getReadTimeoutSeconds()))
                // TCP-level read/write timeout handlers
                .doOnConnected(connection -> {
                    connection.addHandlerLast(new ReadTimeoutHandler(
                            ollamaProperties.getReadTimeoutSeconds(), TimeUnit.SECONDS));
                    connection.addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS));
                });

        return WebClient.builder()
                .baseUrl(ollamaProperties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // Ollama can return large responses for complex prompts — 16MB buffer
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs()
                                .maxInMemorySize(1024 * 1024 * 16))
                        .build())
                .build();
    }
}
