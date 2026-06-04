package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class EnvironmentCapabilityDetector {

    private static final int PROBE_TIMEOUT_SECONDS = 3;
    static final String DEFAULT_DOCKER_SOCKET_PATH = "/var/run/docker.sock";

    private final RuntimeAutoProperties properties;
    private final CommandProbe commandProbe;
    private final String dockerSocketPath;

    @Autowired
    public EnvironmentCapabilityDetector(RuntimeAutoProperties properties) {
        this(properties, new ProcessCommandProbe(), DEFAULT_DOCKER_SOCKET_PATH);
    }

    EnvironmentCapabilityDetector(RuntimeAutoProperties properties, CommandProbe commandProbe, String dockerSocketPath) {
        this.properties = properties;
        this.commandProbe = commandProbe;
        this.dockerSocketPath = dockerSocketPath;
    }

    public EnvironmentCapabilityReport detect() {
        Set<EnvironmentCapability> available = EnumSet.noneOf(EnvironmentCapability.class);
        Set<EnvironmentCapability> missing = EnumSet.noneOf(EnvironmentCapability.class);

        boolean dockerEnabled = properties != null && properties.isDockerRuntimeEnabled();
        if (dockerEnabled) {
            probe(EnvironmentCapability.DOCKER_SOCKET, this::probeDockerSocket, available, missing);
            probe(EnvironmentCapability.DOCKER_CLI, this::probeDockerCli, available, missing);
        } else {
            missing.add(EnvironmentCapability.DOCKER_SOCKET);
            missing.add(EnvironmentCapability.DOCKER_CLI);
        }

        probe(EnvironmentCapability.JDK, this::probeJdk, available, missing);
        probe(EnvironmentCapability.MAVEN, this::probeMaven, available, missing);
        probe(EnvironmentCapability.GRADLE, this::probeGradle, available, missing);
        probe(EnvironmentCapability.WRITABLE_TEMP_DIR, this::probeTempDir, available, missing);

        String summary = buildSummary(available, missing, dockerEnabled);
        log.info("[EnvironmentCapability] Probe complete. dockerEnabled={} available={} missing={}",
                dockerEnabled, available, missing);

        return EnvironmentCapabilityReport.builder()
                .available(available)
                .missing(missing)
                .summary(summary)
                .probedAt(LocalDateTime.now())
                .build();
    }

    private boolean probeDockerSocket() {
        File sock = new File(dockerSocketPath);
        boolean readable = sock.exists() && sock.canRead();
        log.debug("[EnvironmentCapability] Docker socket {} readable={}", dockerSocketPath, readable);
        return readable;
    }

    private boolean probeDockerCli() {
        return commandProbe.run("docker", "info", "--format", "{{.ServerVersion}}");
    }

    private boolean probeJdk() {
        return commandProbe.run("javac", "-version");
    }

    private boolean probeMaven() {
        return commandProbe.run("mvn", "--version");
    }

    private boolean probeGradle() {
        return commandProbe.run("gradle", "--version");
    }

    private boolean probeTempDir() {
        try {
            Path probe = Files.createTempFile("ai-toolcheck-cap-probe-", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            log.debug("[EnvironmentCapability] Temp dir not writable: {}", e.getMessage());
            return false;
        }
    }

    private void probe(EnvironmentCapability cap,
                       java.util.function.BooleanSupplier checker,
                       Set<EnvironmentCapability> available,
                       Set<EnvironmentCapability> missing) {
        try {
            if (checker.getAsBoolean()) {
                available.add(cap);
            } else {
                missing.add(cap);
            }
        } catch (Exception e) {
            log.debug("[EnvironmentCapability] Probe {} threw: {}", cap, e.getMessage());
            missing.add(cap);
        }
    }

    private String buildSummary(Set<EnvironmentCapability> available,
                                Set<EnvironmentCapability> missing,
                                boolean dockerEnabled) {
        if (!available.contains(EnvironmentCapability.WRITABLE_TEMP_DIR)) {
            return "UNSUPPORTED: Temp directory is not writable. No autostart mode can proceed.";
        }
        if (!dockerEnabled) {
            return "UNSUPPORTED: Docker runtime is disabled by config. Set AI_RUNTIME_ENABLED=true and AI_RUNTIME_DOCKER_ENABLED=true.";
        }
        if (available.contains(EnvironmentCapability.DOCKER_CLI)
                && available.contains(EnvironmentCapability.DOCKER_SOCKET)) {
            return "DOCKER mode available. Source containers can be built and managed via Docker socket.";
        }

        StringBuilder sb = new StringBuilder("UNSUPPORTED: ");
        if (missing.contains(EnvironmentCapability.DOCKER_SOCKET)) {
            sb.append("Docker socket not mounted or not readable (mount /var/run/docker.sock). ");
        }
        if (missing.contains(EnvironmentCapability.DOCKER_CLI)) {
            sb.append("Docker CLI not available or cannot reach Docker daemon. ");
        }
        sb.append("Enable backend Docker client and socket mount to use auto-runtime.");
        return sb.toString().trim();
    }

    interface CommandProbe {
        boolean run(String... command);
    }

    static class ProcessCommandProbe implements CommandProbe {
        @Override
        public boolean run(String... command) {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
