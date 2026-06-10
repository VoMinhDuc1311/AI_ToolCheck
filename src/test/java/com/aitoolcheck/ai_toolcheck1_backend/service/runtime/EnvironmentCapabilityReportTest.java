package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentCapability;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EnvironmentCapabilityReport} business logic.
 *
 * <p>These test the report's derived capability flags (canRunDocker, canBuildMaven, etc.)
 * without actually probing the environment.
 */
class EnvironmentCapabilityReportTest {

    @Test
    void canRunDocker_trueWhenBothCliAndSocketAvailable() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET,
                        EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.JDK, EnvironmentCapability.MAVEN, EnvironmentCapability.GRADLE)
        );
        assertThat(report.canRunDocker()).isTrue();
        assertThat(report.canAutoStart()).isTrue();
    }

    @Test
    void canRunDocker_falseWhenSocketMissing() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.DOCKER_SOCKET, EnvironmentCapability.JDK,
                        EnvironmentCapability.MAVEN, EnvironmentCapability.GRADLE)
        );
        assertThat(report.canRunDocker()).isFalse();
    }

    @Test
    void canRunDocker_falseWhenCliMissing() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.DOCKER_SOCKET, EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.JDK,
                        EnvironmentCapability.MAVEN, EnvironmentCapability.GRADLE)
        );
        assertThat(report.canRunDocker()).isFalse();
    }

    @Test
    void canBuildMaven_trueWhenJdkAndMavenPresent() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.JDK, EnvironmentCapability.MAVEN,
                        EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET,
                        EnvironmentCapability.GRADLE)
        );
        assertThat(report.canBuildMaven()).isTrue();
        assertThat(report.canAutoStart()).isTrue();
    }

    @Test
    void canBuildMaven_falseWhenJdkMissing() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.MAVEN, EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.JDK, EnvironmentCapability.DOCKER_CLI,
                        EnvironmentCapability.DOCKER_SOCKET, EnvironmentCapability.GRADLE)
        );
        assertThat(report.canBuildMaven()).isFalse();
    }

    @Test
    void canBuildGradle_trueWhenJdkAndGradlePresent() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.JDK, EnvironmentCapability.GRADLE,
                        EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET,
                        EnvironmentCapability.MAVEN)
        );
        assertThat(report.canBuildGradle()).isTrue();
        assertThat(report.canAutoStart()).isTrue();
    }

    @Test
    void canAutoStart_falseWhenNoBuildPathAvailable() {
        // This is the current EC2 state: JRE-only, no docker socket, no JDK, no Maven, no Gradle
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET,
                        EnvironmentCapability.JDK, EnvironmentCapability.MAVEN, EnvironmentCapability.GRADLE)
        );
        assertThat(report.canRunDocker()).isFalse();
        assertThat(report.canBuildMaven()).isFalse();
        assertThat(report.canBuildGradle()).isFalse();
        assertThat(report.canAutoStart()).isFalse();
    }

    @Test
    void canAutoStart_falseWhenNothingAvailable() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.noneOf(EnvironmentCapability.class),
                EnumSet.allOf(EnvironmentCapability.class)
        );
        assertThat(report.canAutoStart()).isFalse();
    }

    @Test
    void report_hasNonNullSummary() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET,
                        EnvironmentCapability.JDK, EnvironmentCapability.MAVEN, EnvironmentCapability.GRADLE)
        );
        assertThat(report.getSummary()).isNotNull().isNotBlank();
    }

    @Test
    void report_summaryMentionsUnsupportedWhenNoBuildPath() {
        EnvironmentCapabilityReport report = buildReport(
                EnumSet.of(EnvironmentCapability.WRITABLE_TEMP_DIR),
                EnumSet.of(EnvironmentCapability.DOCKER_CLI, EnvironmentCapability.DOCKER_SOCKET,
                        EnvironmentCapability.JDK, EnvironmentCapability.MAVEN, EnvironmentCapability.GRADLE)
        );
        assertThat(report.getSummary()).containsIgnoringCase("UNSUPPORTED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private EnvironmentCapabilityReport buildReport(Set<EnvironmentCapability> available,
                                                    Set<EnvironmentCapability> missing) {
        // Build a human-readable summary consistent with EnvironmentCapabilityDetector logic
        boolean docker = available.contains(EnvironmentCapability.DOCKER_CLI)
                && available.contains(EnvironmentCapability.DOCKER_SOCKET);
        boolean maven = available.contains(EnvironmentCapability.JDK)
                && available.contains(EnvironmentCapability.MAVEN);
        boolean gradle = available.contains(EnvironmentCapability.JDK)
                && available.contains(EnvironmentCapability.GRADLE);

        String summary;
        if (docker) {
            summary = "DOCKER mode available.";
        } else if (maven) {
            summary = "MAVEN_JAR mode available.";
        } else if (gradle) {
            summary = "GRADLE_JAR mode available.";
        } else {
            summary = "UNSUPPORTED: no viable build path. Missing: " + missing;
        }

        return EnvironmentCapabilityReport.builder()
                .available(available)
                .missing(missing)
                .summary(summary)
                .probedAt(java.time.LocalDateTime.now())
                .build();
    }
}
