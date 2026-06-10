package com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentCapability;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Immutable snapshot of the backend host's runtime execution capabilities.
 *
 * <p>Produced by
 * {@link com.aitoolcheck.ai_toolcheck1_backend.service.runtime.EnvironmentCapabilityDetector}
 * and exposed via {@code GET /v1/source-projects/{projectId}/runtime/environment}.
 *
 * <h3>Interpretation:</h3>
 * <ul>
 *   <li>{@link #canRunDocker()} — {@code true} if both {@code DOCKER_CLI} and
 *       {@code DOCKER_SOCKET} are available.</li>
 *   <li>{@link #canBuildMaven()} — {@code true} if JDK + Maven are both present.</li>
 *   <li>{@link #canBuildGradle()} — {@code true} if JDK + Gradle are both present.</li>
 *   <li>{@link #canAutoStart()} — {@code true} if at least one full build+run path works.</li>
 * </ul>
 */
@Getter
@Builder
public class EnvironmentCapabilityReport {

    /** Capabilities confirmed to be present on this host. */
    private final Set<EnvironmentCapability> available;

    /** Capabilities probed but NOT found on this host. */
    private final Set<EnvironmentCapability> missing;

    /** Human-readable summary of what can and cannot run. */
    private final String summary;

    /** When this report was produced. */
    private final LocalDateTime probedAt;

    /**
     * Returns {@code true} if Docker CLI and Docker socket are both available.
     * Prerequisite for Docker image build + container lifecycle management.
     */
    public boolean canRunDocker() {
        return available.contains(EnvironmentCapability.DOCKER_CLI)
                && available.contains(EnvironmentCapability.DOCKER_SOCKET);
    }

    /**
     * Returns {@code true} if JDK and Maven are both present.
     * Prerequisite for building Spring Boot Maven projects into runnable JARs.
     */
    public boolean canBuildMaven() {
        return available.contains(EnvironmentCapability.JDK)
                && available.contains(EnvironmentCapability.MAVEN);
    }

    /**
     * Returns {@code true} if JDK and Gradle are both present.
     * Prerequisite for building Spring Boot Gradle projects into runnable JARs.
     */
    public boolean canBuildGradle() {
        return available.contains(EnvironmentCapability.JDK)
                && available.contains(EnvironmentCapability.GRADLE);
    }

    /**
     * Returns {@code true} if at least one complete build+run path is available.
     * When this is {@code false}, any autostart attempt will yield
     * {@link com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus#ENVIRONMENT_UNSUPPORTED}.
     */
    public boolean canAutoStart() {
        return canRunDocker() || canBuildMaven() || canBuildGradle();
    }
}
