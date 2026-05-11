package com.aitoolcheck.ai_toolcheck1_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for RestTemplate bean with timeout settings.
 * This prevents the application from hanging indefinitely when the target
 * server is unresponsive.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * Connection timeout in milliseconds.
     * Time to establish a connection to the remote host.
     */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /**
     * Read timeout in milliseconds.
     * Time to wait for data to be returned after connection is established.
     */
    private static final int READ_TIMEOUT_MS = 15000;

    /**
     * Creates a RestTemplate bean with configured timeouts.
     * Uses SimpleClientHttpRequestFactory to set connect and read timeouts.
     * Wraps with BufferingClientHttpRequestFactory to allow response body to be
     * read multiple times.
     *
     * @return configured RestTemplate bean
     */
    @Bean
    public RestTemplate restTemplate() {
        // Create a SimpleClientHttpRequestFactory with timeout settings
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        // Wrap with BufferingClientHttpRequestFactory to allow reading response body
        // multiple times
        BufferingClientHttpRequestFactory bufferingFactory = new BufferingClientHttpRequestFactory(factory);

        return new RestTemplate(bufferingFactory);
    }
}