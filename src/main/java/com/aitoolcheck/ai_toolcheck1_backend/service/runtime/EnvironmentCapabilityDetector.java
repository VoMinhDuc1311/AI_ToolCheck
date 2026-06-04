package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Probes the host environment for runtime execution capabilities.
 *
 * <p>Results are <strong>not cached</strong>: every call re-probes. Callers that
 * need caching (e.g. controller endpoints) should cache the report themselves.
 *
 * <h3>Probes performed:</h3>
 * <ol>
 *   <li>{@code DOCKER_CLI} — executes {@code docker info --format '{{.ServerVersion}}'}.</li>
 *   <li>{@code DOCKER_SOCKET} — checks readability of {@code /var/run/docker.sock}.</li>
 *   <li>{@code JDK} — executes {@code javac -version}.</li>
 *   <li>{@code MAVEN} — executes {@code mvn --version}.</li>
 *   <li>{@code GRADLE} — executes {@code gradle --version}.</li>
 *   <li>{@code WRITABLE_TEMP_DIR} — creates and deletes a probe file in {@code java.io.tmpdir}.</li>
 * </ol>
 *
 * <p>Each probe uses a hard 3-second timeout to prevent blocking the calling thread.
 */
@Service
@Slf4j
public class EnvironmentCapabilityDetector {

    private static final int PROBE_TIMEOUT_SECONDS = 3;
    private static final String DOCKER_SOCKET_PATH = "/var/run/docker.sock";

    /**
     * Probes all capabilities and returns a fresh {@link EnvironmentCapabilityReport}.
     *
     * <p>This method is intentionally synchronous and may take up to
     * {@code 6 × PROBE_TIMEOUT_SECONDS} in the worst case (all probes time out).
     * Call from a background thread or at startup only.
     */
    public EnvironmentCapabilityReport detect() {
        Set<EnvironmentCapability> available = EnumSet.noneOf(EnvironmentCapability.class);
        Set<EnvironmentCapability> missing = EnumSet.noneOf(EnvironmentCapability.class);

        probe(EnvironmentCapability.DOCKER_SOCKET, this::probeDockerSocket, available, missing);
        probe(EnvironmentCapability.DOCKER_CLI, this::probeDockerCli, available, missing);
        probe(EnvironmentCapability.JDK, this::probeJdk, available, missing);
        probe(EnvironmentCapability.MAVEN, this::probeMaven, available, missing);
        probe(EnvironmentCapability.GRADLE, this::probeGradle, available, missing);
        probe(EnvironmentCapability.WRITABLE_TEMP_DIR, this::probeTempDir, available, missing);

        String summary = buildSummary(available, missing);
        log.info("[EnvironmentCapability] Probe complete. Available={} Missing={}", available, missing);

        return EnvironmentCapabilityReport.builder()
                .available(available)
                .missing(missing)
                .summary(summary)
                .probedAt(LocalDateTime.now())
                .build();
    }

    // ── Individual probes ─────────────────────────────────────────────────────

    private boolean probeDockerSocket() {
        File sock = new File(DOCKER_SOCKET_PATH);
        boolean readable = sock.exists() && sock.canRead();
        log.debug("[EnvironmentCapability] Docker socket {} readable={}", DOCKER_SOCKET_PATH, readable);
        return readable;
    }

    private boolean probeDockerCli() {
        return runCommand("docker", "info", "--format", "{{.ServerVersion}}");
    }

    private boolean probeJdk() {
        return runCommand("javac", "-version");
    }

    private boolean probeMaven() {
        return runCommand("mvn", "--version");
    }

    private boolean probeGradle() {
        return runCommand("gradle", "--version");
    }

    private boolean probeTempDir() {
        try {
            Path probe = Files.createTempFile("ai-toolcheck-cap-probe-", ".tmp");
            Files.deleteIfExists(probe);
            log.debug("[EnvironmentCapability] Writable temp dir OK: {}", probe.getParent());
            return true;
        } catch (IOException e) {
            log.debug("[EnvironmentCapability] Temp dir not writable: {}", e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /**
     * Runs an external command with a timeout and returns {@code true} if it exits 0.
     */
    private boolean runCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("[EnvironmentCapability] Command '{}' timed out after {}s",
                        command[0], PROBE_TIMEOUT_SECONDS);
                return false;
            }
            int exit = process.exitValue();
            log.debug("[EnvironmentCapability] Command '{}' exited with code {}", command[0], exit);
            return exit == 0;
        } catch (IOException e) {
            // Command not found — normal for environments without the tool
            log.debug("[EnvironmentCapability] Command '{}' not found: {}", command[0], e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[EnvironmentCapability] Command '{}' probe interrupted", command[0]);
            return false;
        }
    }

    private String buildSummary(Set<EnvironmentCapability> available,
                                Set<EnvironmentCapability> missing) {
        boolean docker = available.contains(EnvironmentCapability.DOCKER_CLI)
                && available.contains(EnvironmentCapability.DOCKER_SOCKET);
        boolean maven = available.contains(EnvironmentCapability.JDK)
                && available.contains(EnvironmentCapability.MAVEN);
        boolean gradle = available.contains(EnvironmentCapability.JDK)
                && available.contains(EnvironmentCapability.GRADLE);

        if (!available.contains(EnvironmentCapability.WRITABLE_TEMP_DIR)) {
            return "UNSUPPORTED: Temp directory is not writable. No autostart mode can proceed.";
        }

        if (docker) {
            return "DOCKER mode available. Source containers can be built and managed via Docker socket.";
        }

        if (maven) {
            return "MAVEN_JAR mode available. Maven projects can be built and run as JAR processes.";
        }

        if (gradle) {
            return "GRADLE_JAR mode available. Gradle projects can be built and run as JAR processes.";
        }

        // Describe why we are unsupported
        StringBuilder sb = new StringBuilder("UNSUPPORTED: ");
        if (missing.contains(EnvironmentCapability.DOCKER_SOCKET)) {
            sb.append("Docker socket not mounted (add /var/run/docker.sock volume). ");
        } else if (missing.contains(EnvironmentCapability.DOCKER_CLI)) {
            sb.append("Docker CLI not available in container. ");
        }
        if (missing.contains(EnvironmentCapability.JDK)) {
            sb.append("JDK not installed (only JRE present — need eclipse-temurin:21-jdk or equivalent). ");
        } else if (missing.contains(EnvironmentCapability.MAVEN) && missing.contains(EnvironmentCapability.GRADLE)) {
            sb.append("Neither Maven nor Gradle found in PATH. ");
        }
        sb.append("Upgrade the backend image or mount Docker socket to enable auto-runtime.");
        return sb.toString().trim();
    }
}
