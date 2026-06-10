package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for source auto-runtime.
 *
 * <p>Primary binding prefix: {@code ai.runtime.*}. The existing Java class name
 * is retained because services already depend on it.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.runtime")
public class RuntimeAutoProperties {

    /** Master switch for auto-runtime. Default: false. */
    private boolean enabled = false;

    /** Docker-specific switch. Both enabled and docker.enabled must be true. */
    private Docker docker = new Docker();

    /** Backward-compatible direct network property; docker.network wins if set. */
    private String dockerNetwork = "ai-toolcheck-network";

    /** Container-side application port fallback. */
    private int internalPort = 8080;

    /** Host port range for runtime containers. */
    private int portMin = 18080;
    private int portMax = 18999;

    /** Prefix used for generated Docker images and container names. */
    private String containerPrefix = "aitc-runtime";

    /** Hostname/IP used in publicBaseUrl. Override in production. */
    private String publicHost = "127.0.0.1";

    private int buildTimeoutSeconds = 300;
    private int startTimeoutSeconds = 120;
    private int healthTimeoutSeconds = 5;
    private int maxActiveRuntimes = 5;

    public boolean isDockerRuntimeEnabled() {
        return enabled && docker != null && docker.isEnabled();
    }

    public String getDockerNetwork() {
        if (docker != null && docker.getNetwork() != null && !docker.getNetwork().isBlank()) {
            return docker.getNetwork();
        }
        return dockerNetwork;
    }

    public void setDockerNetwork(String dockerNetwork) {
        this.dockerNetwork = dockerNetwork;
        if (this.docker == null) {
            this.docker = new Docker();
        }
        this.docker.setNetwork(dockerNetwork);
    }

    public int getStartupTimeoutSeconds() {
        return startTimeoutSeconds;
    }

    public void setStartupTimeoutSeconds(int startupTimeoutSeconds) {
        this.startTimeoutSeconds = startupTimeoutSeconds;
    }

    public int getHostPortRangeStart() {
        return portMin;
    }

    public void setHostPortRangeStart(int hostPortRangeStart) {
        this.portMin = hostPortRangeStart;
    }

    public int getHostPortRangeEnd() {
        return portMax;
    }

    public void setHostPortRangeEnd(int hostPortRangeEnd) {
        this.portMax = hostPortRangeEnd;
    }

    @Data
    public static class Docker {
        private boolean enabled = false;
        private String network;
    }
}
