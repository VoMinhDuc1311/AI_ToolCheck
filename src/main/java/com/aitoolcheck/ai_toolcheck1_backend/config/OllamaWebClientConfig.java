package com.aitoolcheck.ai_toolcheck1_backend.config;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import io.netty.channel.ChannelOption;
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
 *
 * <p><strong>Timeout strategy:</strong>
 * <ul>
 *   <li>No global Netty {@code ReadTimeoutHandler} is registered here — it would clamp
 *       all requests to the global value and prevent skill-specific timeouts (e.g. 180s
 *       for {@code enrich_api_doc}) from taking effect.</li>
 *   <li>A loose Reactor {@code responseTimeout} is set to a generous ceiling
 *       ({@code globalReadTimeoutSeconds * 3} or at least 300s) purely as a
 *       last-resort safety net. The effective per-request deadline is driven by
 *       {@code Mono.timeout()} in {@link com.aitoolcheck.ai_toolcheck1_backend.service.impl.OllamaApiClientServiceImpl}.</li>
 *   <li>TCP write timeout (30s) is kept because write hangs are independent of model
 *       inference time.</li>
 * </ul>
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
        int globalReadTimeoutSeconds = ollamaProperties.getReadTimeoutSeconds();

        // Safety-net ceiling: generously above any skill-specific timeout so it
        // never fires before Mono.timeout() does.  Minimum 300s.
        int safetyNetSeconds = Math.max(300, globalReadTimeoutSeconds * 3);

        log.info("[OllamaWebClientConfig] Initializing OllamaWebClient — baseUrl={} primaryModel={} " +
                        "embedModel={} globalReadTimeoutSeconds={} safetyNetCeilingSeconds={}",
                ollamaProperties.getBaseUrl(),
                ollamaProperties.getPrimaryModel(),
                ollamaProperties.getEmbedModel(),
                globalReadTimeoutSeconds,
                safetyNetSeconds);

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
                // Safety-net response timeout: loose ceiling only.
                // Per-request effective timeout is controlled by Mono.timeout()
                // in OllamaApiClientServiceImpl so skill-specific values apply.
                .responseTimeout(Duration.ofSeconds(safetyNetSeconds))
                // TCP-level write timeout only — read timeout removed intentionally
                // to allow per-request Mono.timeout() to be the effective deadline.
                .doOnConnected(connection ->
                        connection.addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

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
