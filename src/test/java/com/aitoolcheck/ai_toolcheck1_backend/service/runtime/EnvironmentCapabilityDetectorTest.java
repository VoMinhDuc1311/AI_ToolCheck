package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentCapabilityDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsDockerCliAndSocketWhenEnabledAndAvailable() throws Exception {
        Path socket = Files.createFile(tempDir.resolve("docker.sock"));
        RuntimeAutoProperties properties = enabledDockerProperties();

        EnvironmentCapabilityDetector detector = new EnvironmentCapabilityDetector(
                properties,
                command -> command[0].equals("docker"),
                socket.toString()
        );

        var report = detector.detect();

        assertThat(report.getAvailable()).contains(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET);
        assertThat(report.canRunDocker()).isTrue();
    }

    @Test
    void reportsMissingDockerSocket() {
        RuntimeAutoProperties properties = enabledDockerProperties();
        EnvironmentCapabilityDetector detector = new EnvironmentCapabilityDetector(
                properties,
                command -> command[0].equals("docker"),
                tempDir.resolve("missing.sock").toString()
        );

        var report = detector.detect();

        assertThat(report.getMissing()).contains(EnvironmentCapability.DOCKER_SOCKET);
        assertThat(report.canRunDocker()).isFalse();
    }

    @Test
    void reportsDockerDisabledByConfig() throws Exception {
        Path socket = Files.createFile(tempDir.resolve("docker.sock"));
        RuntimeAutoProperties properties = enabledDockerProperties();
        properties.getDocker().setEnabled(false);

        EnvironmentCapabilityDetector detector = new EnvironmentCapabilityDetector(
                properties,
                command -> true,
                socket.toString()
        );

        var report = detector.detect();

        assertThat(report.getMissing()).contains(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET);
        assertThat(report.getSummary()).contains("disabled by config");
        assertThat(report.canRunDocker()).isFalse();
    }

    @Test
    void canRunDockerRequiresEnabledCliAndSocket() throws Exception {
        Path socket = Files.createFile(tempDir.resolve("docker.sock"));
        RuntimeAutoProperties properties = enabledDockerProperties();
        properties.setEnabled(false);

        EnvironmentCapabilityDetector detector = new EnvironmentCapabilityDetector(
                properties,
                command -> true,
                socket.toString()
        );

        assertThat(detector.detect().canRunDocker()).isFalse();
    }

    private RuntimeAutoProperties enabledDockerProperties() {
        RuntimeAutoProperties properties = new RuntimeAutoProperties();
        properties.setEnabled(true);
        properties.getDocker().setEnabled(true);
        return properties;
    }
}
