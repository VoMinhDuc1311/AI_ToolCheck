package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DockerRuntimeOrchestrator.ProcessCommandExecutor}.
 *
 * <p>Tests use real OS processes (platform-guarded where necessary) to verify
 * the concurrent stream-draining fix and success/failure semantics.</p>
 *
 * <p><b>Key invariants under test:</b>
 * <ul>
 *   <li>Exit code 0 → success, even when stderr has content (Docker deprecation warnings).</li>
 *   <li>Exit code ≠ 0 → failure, with exitCode and partial output in result.</li>
 *   <li>Timeout → destroyForcibly, timedOut=true, BUILD_FAILED.</li>
 *   <li>stdout and stderr are drained concurrently — no pipe-buffer deadlock.</li>
 * </ul>
 */
class ProcessCommandExecutorTest {

    private final DockerRuntimeOrchestrator.ProcessCommandExecutor executor =
            new DockerRuntimeOrchestrator.ProcessCommandExecutor();

    // ── Windows (cmd.exe) ────────────────────────────────────────────────────

    /**
     * DEPRECATED warnings on stderr but exit 0 → must be SUCCESS.
     * Simulates Docker's "DEPRECATED: The legacy builder is deprecated..." line.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dockerBuild_stderrWarningExitZero_isSuccess_windows() throws Exception {
        // cmd writes to stderr via 1>&2, exits 0
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "cmd", "/c",
                "echo DEPRECATED: The legacy builder is deprecated 1>&2 && echo BUILD_OUTPUT && exit 0"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dockerBuild_nonZeroExit_isBuildFailedWithExitCode_windows() {
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "cmd", "/c", "exit 1"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dockerBuild_doesNotFailOnlyBecauseStderrHasContent_windows() {
        // Writes to stderr but exits 0 — must succeed
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "cmd", "/c", "echo warning on stderr 1>&2 && exit 0"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dockerBuild_timeoutKillsProcessAndMarksFailed_windows() {
        // ping -n 10 sleeps ~9s on Windows (pings localhost 10 times with 1s interval)
        DockerRuntimeOrchestrator.CommandResult result = executor.run(1, List.of(
                "cmd", "/c", "ping -n 10 127.0.0.1 > nul"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.output()).contains("timed out");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dockerBuild_usesConfiguredBuildTimeout_windows() {
        // If we run a fast command with a generous timeout, it should NOT time out
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "cmd", "/c", "echo ok"
        ));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.elapsedMs()).isLessThan(10_000);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dockerBuild_waitsForProcessCompletionBeforeReturning_windows() {
        // Command sleeps ~2s then prints a marker; result must contain marker (proves wait-for-completion)
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "cmd", "/c", "ping -n 3 127.0.0.1 > nul && echo DONE"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.stdout()).contains("DONE");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dockerBuild_logsExitCodeAndElapsedTime_windows() {
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "cmd", "/c", "echo hi"
        ));

        // diagnostic() must contain exitCode and elapsedMs
        String diag = result.diagnostic();
        assertThat(diag).contains("exitCode=0");
        assertThat(diag).contains("elapsedMs=");
        assertThat(diag).contains("timedOut=false");
    }

    // ── Linux / Mac ──────────────────────────────────────────────────────────

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void dockerBuild_stderrWarningExitZero_isSuccess_unix() {
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "bash", "-c",
                "echo 'DEPRECATED: The legacy builder is deprecated' >&2 ; echo 'BUILD_OUTPUT' ; exit 0"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void dockerBuild_nonZeroExit_isBuildFailedWithExitCode_unix() {
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "bash", "-c", "exit 2"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void dockerBuild_doesNotFailOnlyBecauseStderrHasContent_unix() {
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "bash", "-c", "echo 'warning on stderr' >&2 ; exit 0"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void dockerBuild_timeoutKillsProcessAndMarksFailed_unix() {
        DockerRuntimeOrchestrator.CommandResult result = executor.run(1, List.of(
                "bash", "-c", "sleep 30"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.output()).contains("timed out");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void dockerBuild_usesConfiguredBuildTimeout_unix() {
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "bash", "-c", "echo ok"
        ));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.elapsedMs()).isLessThan(10_000);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void dockerBuild_waitsForProcessCompletionBeforeReturning_unix() {
        // Sleeps 2s then echoes a marker — result must contain marker
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "bash", "-c", "sleep 2 && echo DONE"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.stdout()).contains("DONE");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void dockerBuild_logsExitCodeAndElapsedTime_unix() {
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "bash", "-c", "echo hi"
        ));

        String diag = result.diagnostic();
        assertThat(diag).contains("exitCode=0");
        assertThat(diag).contains("elapsedMs=");
        assertThat(diag).contains("timedOut=false");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void dockerBuild_largeOutput_drainsConcurrentlyWithoutDeadlock_unix() {
        // Generates ~200 KB of output — without concurrent draining this would deadlock
        // (Linux default pipe buffer is 64 KB).
        DockerRuntimeOrchestrator.CommandResult result = executor.run(30, List.of(
                "bash", "-c", "yes 'x' | head -c 204800"   // 200 KB of 'x\n' lines
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
        // stdout was captured (may be tailed to TAIL_CHARS, but must be non-empty)
        assertThat(result.stdout()).isNotBlank();
    }

    // ── CommandResult unit tests (platform-neutral) ──────────────────────────

    @Test
    void commandResult_exitZero_isSuccess() {
        var r = new DockerRuntimeOrchestrator.CommandResult(true, 0, "ok", false, 100L, "ok", "");
        assertThat(r.success()).isTrue();
        assertThat(r.timedOut()).isFalse();
    }

    @Test
    void commandResult_exitNonZero_isNotSuccess() {
        var r = new DockerRuntimeOrchestrator.CommandResult(false, 1, "err", false, 100L, "", "some error");
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(1);
    }

    @Test
    void commandResult_timedOut_outputContainsTimedOutMessage() {
        var r = new DockerRuntimeOrchestrator.CommandResult(false, -1,
                "Process timed out after 5000ms (limit=5s). stdout=[] stderr=[]",
                true, 5000L, "", "");
        assertThat(r.timedOut()).isTrue();
        assertThat(r.output()).contains("timed out");
        assertThat(r.success()).isFalse();
    }

    @Test
    void commandResult_legacyConstructor_setsDefaults() {
        var r = new DockerRuntimeOrchestrator.CommandResult(true, 0, "output");
        assertThat(r.timedOut()).isFalse();
        assertThat(r.elapsedMs()).isEqualTo(-1L);
        assertThat(r.stdout()).isEqualTo("output");
        assertThat(r.stderr()).isEmpty();
    }

    @Test
    void commandResult_summary_masksSecrets() {
        var r = new DockerRuntimeOrchestrator.CommandResult(false, 1,
                "token=secret123 build failed", false, 100L,
                "token=secret123 build failed", "");
        assertThat(r.summary()).contains("token=***");
        assertThat(r.summary()).doesNotContain("secret123");
    }

    @Test
    void commandResult_diagnostic_containsAllFields() {
        var r = new DockerRuntimeOrchestrator.CommandResult(true, 0, "ok",
                false, 1234L, "stdout-content", "stderr-content");
        String diag = r.diagnostic();
        assertThat(diag).contains("exitCode=0");
        assertThat(diag).contains("timedOut=false");
        assertThat(diag).contains("elapsedMs=1234");
        assertThat(diag).contains("stdout-content");
        assertThat(diag).contains("stderr-content");
    }
}
