package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the auto-runtime feature.
 *
 * <p>Bound from {@code runtime.auto.*} in {@code application.yaml}.
 *
 * <h3>Port allocation:</h3>
 * <p>When starting auto-runtimes via Docker, host ports are allocated from
 * {@code [hostPortRangeStart, hostPortRangeEnd]}. The first available port
 * in this range is selected. Default range: 18080–18999 (920 slots).
 */
@Data
@Component
@ConfigurationProperties(prefix = "runtime.auto")
public class RuntimeAutoProperties {

    /** Whether the auto-runtime feature is enabled at all. Default: {@code false}. */
    private boolean enabled = false;

    /**
     * Docker network name to attach runtime containers to.
     * Must exist before the first container is started.
     * Default: {@code ai-toolcheck-runtime}.
     */
    private String dockerNetwork = "ai-toolcheck-runtime";

    /**
     * The internal (container-side) port that the source application exposes.
     * Used as the container port in {@code docker run -p HOST_PORT:INTERNAL_PORT}.
     * Default: {@code 8080}.
     */
    private int internalPort = 8080;

    /**
     * Maximum time (in seconds) allowed for {@code docker build} to complete.
     * Default: {@code 300} (5 minutes).
     */
    private int buildTimeoutSeconds = 300;

    /**
     * Maximum time (in seconds) to wait for the started container to pass
     * health checks before marking it as {@code BUILD_FAILED}.
     * Default: {@code 120} (2 minutes).
     */
    private int startupTimeoutSeconds = 120;

    /**
     * Maximum number of concurrently running auto-runtime containers.
     * Requests beyond this limit will return a {@code BUILD_QUEUED} or error response.
     * Default: {@code 5}.
     */
    private int maxActiveRuntimes = 5;

    /**
     * Start of the host port range used for allocating ports to auto-runtime containers.
     * Default: {@code 18080}.
     */
    private int hostPortRangeStart = 18080;

    /**
     * End (inclusive) of the host port range for auto-runtime containers.
     * Default: {@code 18999}.
     */
    private int hostPortRangeEnd = 18999;
}
