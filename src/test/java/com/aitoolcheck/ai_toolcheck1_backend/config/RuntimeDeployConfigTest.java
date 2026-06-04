package com.aitoolcheck.ai_toolcheck1_backend.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDeployConfigTest {

    @Test
    void dockerfileInstallsDockerCli() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile).contains("docker-ce-cli");
        assertThat(dockerfile).doesNotContain("dockerd");
    }

    @Test
    void prodComposeMountsDockerSocketAndRuntimeEnv() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.prod.yml"));

        assertThat(compose).contains("/var/run/docker.sock:/var/run/docker.sock");
        assertThat(compose).contains("AI_RUNTIME_DOCKER_ENABLED");
        assertThat(compose).contains("AI_RUNTIME_PORT_MIN");
        assertThat(compose).contains("AI_RUNTIME_PORT_MAX");
        assertThat(compose).contains("AI_RUNTIME_DOCKER_NETWORK");
        assertThat(compose).contains("AI_RUNTIME_CONTAINER_PREFIX");
        assertThat(compose).contains("AI_RUNTIME_PUBLIC_HOST");
    }

    @Test
    void prodRuntimeDefaultsAreSafeAndUseProdNetwork() throws Exception {
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yaml"));

        assertThat(prod).contains("enabled: ${AI_RUNTIME_ENABLED:false}");
        assertThat(prod).contains("enabled: ${AI_RUNTIME_DOCKER_ENABLED:false}");
        assertThat(prod).contains("network: ${AI_RUNTIME_DOCKER_NETWORK:ai-toolcheck-network}");
        assertThat(prod).contains("port-min: ${AI_RUNTIME_PORT_MIN:18080}");
        assertThat(prod).contains("port-max: ${AI_RUNTIME_PORT_MAX:18999}");
    }
}
