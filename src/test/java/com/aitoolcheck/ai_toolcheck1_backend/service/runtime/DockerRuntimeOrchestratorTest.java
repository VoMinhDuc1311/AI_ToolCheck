package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerRuntimeOrchestratorTest {

    private SourceRuntimeRepository runtimeRepository;
    private ApiEndpointRepository endpointRepository;
    private RuntimeSourceMaterializer materializer;
    private RuntimeAutoProperties properties;
    private com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeLifecycleService lifecycleService;
    private RuntimeStartWorker runtimeStartWorker;
    private DockerRuntimeOrchestrator orchestrator;
    private RecordingCommandExecutor commands;
    private SourceProject project;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        runtimeRepository = mock(SourceRuntimeRepository.class);
        endpointRepository = mock(ApiEndpointRepository.class);
        materializer = mock(RuntimeSourceMaterializer.class);
        lifecycleService = mock(com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeLifecycleService.class);
        runtimeStartWorker = mock(RuntimeStartWorker.class);

        properties = new RuntimeAutoProperties();
        properties.setEnabled(true);
        properties.getDocker().setEnabled(true);
        properties.getDocker().setNetwork("prod-net");
        properties.setContainerPrefix("AITC Runtime_Unsafe");
        properties.setPublicHost("runtime.example.com");
        properties.setPortMin(18080);
        properties.setPortMax(18080);
        properties.setStartTimeoutSeconds(1);

        when(runtimeRepository.save(any(SourceRuntime.class))).thenAnswer(inv -> inv.getArgument(0));
        when(endpointRepository.findBySourceProjectIdAndActiveFlagTrue(any())).thenReturn(List.of());

        final SourceRuntime[] activeRuntime = new SourceRuntime[1];
        org.mockito.Mockito.when(lifecycleService.createBuildingRuntime(any(), any(), any())).thenAnswer(inv -> {
            SourceProject p = inv.getArgument(0);
            BuildStrategy s = inv.getArgument(2);
            SourceRuntime r = SourceRuntime.builder()
                    .id(UUID.randomUUID())
                    .sourceProject(p)
                    .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                    .runtimeStatus(RuntimeStatus.BUILDING)
                    .buildStrategyRequested(s)
                    .build();
            activeRuntime[0] = r;
            return r;
        });

        org.mockito.Mockito.doAnswer(inv -> {
            if (activeRuntime[0] != null) {
                activeRuntime[0].setRuntimeStatus(RuntimeStatus.BUILD_FAILED);
                activeRuntime[0].setLastError(inv.getArgument(1));
            }
            return null;
        }).when(lifecycleService).markBuildFailed(any(), any());

        org.mockito.Mockito.doAnswer(inv -> {
            if (activeRuntime[0] != null) {
                activeRuntime[0].setRuntimeStatus(RuntimeStatus.STARTING);
                activeRuntime[0].setContainerName(inv.getArgument(1));
                activeRuntime[0].setImageName(inv.getArgument(2));
                activeRuntime[0].setDockerfileSource(inv.getArgument(3));
                activeRuntime[0].setBuildStrategyUsed(inv.getArgument(4));
                activeRuntime[0].setFallbackReason(inv.getArgument(5));
            }
            return null;
        }).when(lifecycleService).markStarting(any(), any(), any(), any(), any(), any(), any());

        org.mockito.Mockito.doAnswer(inv -> {
            if (activeRuntime[0] != null) {
                activeRuntime[0].setRuntimeStatus(RuntimeStatus.START_FAILED);
                activeRuntime[0].setLastError(inv.getArgument(1));
            }
            return null;
        }).when(lifecycleService).markStartFailed(any(), any());

        org.mockito.Mockito.doAnswer(inv -> {
            if (activeRuntime[0] != null) {
                activeRuntime[0].setRuntimeStatus(RuntimeStatus.UNHEALTHY);
                activeRuntime[0].setLastHealthStatus(inv.getArgument(1));
                activeRuntime[0].setLastError(inv.getArgument(2));
            }
            return null;
        }).when(lifecycleService).markUnhealthy(any(), any(), any());

        org.mockito.Mockito.doAnswer(inv -> {
            if (activeRuntime[0] != null) {
                activeRuntime[0].setRuntimeStatus(RuntimeStatus.UP);
                activeRuntime[0].setPublicBaseUrl(inv.getArgument(1));
                activeRuntime[0].setDetectedPort(inv.getArgument(2));
                activeRuntime[0].setContainerName(inv.getArgument(3));
                activeRuntime[0].setLastHealthStatus(inv.getArgument(4));
            }
            return null;
        }).when(lifecycleService).markUp(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());

        org.mockito.Mockito.doAnswer(inv -> {
            UUID runtimeId = inv.getArgument(0);
            SourceProject p = inv.getArgument(1);
            RuntimeDetectionResult det = inv.getArgument(2);
            BuildStrategy strat = inv.getArgument(3);
            DockerRuntimeOrchestrator orch = inv.getArgument(4);
            orch.runStartSynchronously(runtimeId, p, det, strat);
            return null;
        }).when(runtimeStartWorker).runStartAsync(any(), any(), any(), any(), any());

        orchestrator = new DockerRuntimeOrchestrator(
                runtimeRepository,
                mock(com.aitoolcheck.ai_toolcheck1_backend.repository.SourceUploadVersionRepository.class),
                endpointRepository,
                materializer,
                properties,
                lifecycleService,
                runtimeStartWorker
        );
        commands = new RecordingCommandExecutor();
        orchestrator.setCommandExecutor(commands);
        orchestrator.setHealthProbe((url, timeout) -> 200);

        project = new SourceProject();
        project.setId(UUID.randomUUID());
    }

    @Test
    void commandBuilderUsesConfiguredNetworkAndNoArbitraryUserCommand() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));

        orchestrator.start(project, supportedMaven());

        assertThat(commands.commands.stream()
                .filter(command -> command.size() > 2 && command.get(1).equals("run"))
                .toList()).anySatisfy(command -> {
            assertThat(command).containsSubsequence(
                    "docker", "run", "-d",
                    "--name", command.get(4)
            );
            assertThat(command).contains(
                    "-p", "18080:8080",
                    "--network", "prod-net",
                    "--restart", "no",
                    "-l", "ai-toolcheck.managed=true"
            );
        });
        assertThat(commands.commands.stream().flatMap(List::stream)).doesNotContain("sh", "-c");
    }

    @Test
    void sanitizesContainerNameAndImageTag() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getContainerName()).startsWith("aitc-runtime-unsafe-");
        assertThat(result.getImageName()).startsWith("aitc-runtime-unsafe:");
        assertThat(result.getContainerName()).doesNotContain(" ");
    }

    @Test
    void usesUploadedDockerfileWhenPresent() throws Exception {
        Path root = materializedRoot();
        Files.writeString(root.resolve("Dockerfile"), "FROM eclipse-temurin:21-jre\n");

        var plan = orchestrator.selectDockerfile(root, supportedMaven(), 8080, BuildStrategy.AUTO);

        assertThat(plan.dockerfilePath().getFileName().toString()).isEqualTo("Dockerfile");
    }

    @Test
    void startMarksUpOnlyAfterHealthProbePasses() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        when(endpointRepository.findBySourceProjectIdAndActiveFlagTrue(project.getId()))
                .thenReturn(List.of(ApiEndpoint.builder()
                        .httpMethod(HttpMethod.GET)
                        .endpointPath("/greeting")
                        .build()));
        orchestrator.setHealthProbe((url, timeout) -> url.endsWith("/greeting") ? 200 : 404);

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(result.getPublicBaseUrl()).isEqualTo("http://runtime.example.com:18080/api");
        assertThat(result.getLastHealthStatus()).isEqualTo("UP:OPENAPI_PROBE:/greeting:200");
    }

    @Test
    void buildFailMarksBuildFailedAndCleansImage() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        commands.failBuild = true;

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(result.getLastError()).contains("docker build failed");
        assertThat(commands.commands).anyMatch(command -> command.contains("rmi"));
    }

    @Test
    void runFailMarksUnhealthyAndCleansContainer() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        commands.failRun = true;

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.START_FAILED);
        assertThat(result.getLastError()).contains("docker run failed");
        assertThat(commands.commands).anyMatch(command -> command.contains("rm") && command.contains("-f"));
    }

    @Test
    void healthFailMarksUnhealthyAndCleansContainer() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        orchestrator.setHealthProbe((url, timeout) -> 503);

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.UNHEALTHY);
        assertThat(result.getLastHealthStatus()).isEqualTo("DOWN:timeout");
        assertThat(commands.commands).anyMatch(command -> command.contains("rm") && command.contains("-f"));
    }

    @Test
    void asyncDispatchRejected_marksRuntimeBuildFailed() {
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("queue full"))
                .when(runtimeStartWorker).runStartAsync(any(), any(), any(), any(), any());

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(result.getLastError()).contains("dispatch failed");
    }

    @Test
    void stopDuringStarting_cleansContainerAndDoesNotMarkUp() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        when(lifecycleService.isStartCancelled(any()))
                .thenReturn(false, false, false, true);

        orchestrator.start(project, supportedMaven());

        assertThat(commands.commands).anyMatch(command -> command.contains("rm") && command.contains("-f"));
        verify(lifecycleService, never()).markUp(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    @Test
    void workerBeforeMarkUp_checksRuntimeStillActive() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        when(lifecycleService.isStartCancelled(any()))
                .thenReturn(false, false, false, false, false, false, true);

        orchestrator.start(project, supportedMaven());

        assertThat(commands.commands).anyMatch(command -> command.contains("rm") && command.contains("-f"));
        verify(lifecycleService, never()).markUp(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    @Test
    void startupHealthProbe_usesCustomHealthPathFirst() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        SourceRuntime runtime = SourceRuntime.builder()
                .healthCheckPath("/greeting")
                .build();
        when(lifecycleService.findFresh(any())).thenReturn(java.util.Optional.of(runtime));
        List<String> probed = new ArrayList<>();
        orchestrator.setHealthProbe((url, timeout) -> {
            probed.add(url);
            return url.endsWith("/greeting") ? 200 : 404;
        });

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(probed.get(0)).endsWith("/greeting");
    }

    @Test
    void stopRemovesContainerAndMarksStopped() {
        SourceRuntime runtime = SourceRuntime.builder()
                .sourceProject(project)
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(RuntimeStatus.UP)
                .containerName("aitc-runtime-abc")
                .build();

        SourceRuntime stopped = orchestrator.stop(runtime);

        assertThat(stopped.getRuntimeStatus()).isEqualTo(RuntimeStatus.STOPPED);
        assertThat(commands.commands).anyMatch(command -> command.equals(List.of("docker", "rm", "-f", "aitc-runtime-abc")));
    }

    @Test
    void stopIsIdempotent() {
        SourceRuntime runtime = SourceRuntime.builder()
                .sourceProject(project)
                .runtimeStatus(RuntimeStatus.STOPPED)
                .containerName("aitc-runtime-abc")
                .build();

        SourceRuntime stopped = orchestrator.stop(runtime);

        assertThat(stopped.getRuntimeStatus()).isEqualTo(RuntimeStatus.STOPPED);
        assertThat(commands.commands).isEmpty();
    }

    private Path materializedRoot() throws Exception {
        Path root = Files.createTempDirectory(tempDir, "runtime-source-");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        return root;
    }

    private MaterializedRuntimeSource materialized(Path root) {
        return MaterializedRuntimeSource.builder()
                .projectId(project.getId())
                .rootDir(root)
                .materializedFiles(List.of("pom.xml"))
                .build();
    }

    private RuntimeDetectionResult supportedMaven() {
        return RuntimeDetectionResult.builder()
                .runtimeType(RuntimeType.SPRING_BOOT_MAVEN)
                .supported(true)
                .detectedPort(8080)
                .contextPath("/api")
                .build();
    }

    // ── Nested source root tests (hotfix) ─────────────────────────────────────

    @Test
    void resolveEffectiveBuildRoot_nullProjectRoot_returnsMaterializedRoot() throws Exception {
        Path root = materializedRoot();
        RuntimeDetectionResult detection = supportedMaven(); // projectRoot is null

        Path result = orchestrator.resolveEffectiveBuildRoot(root, detection);

        assertThat(result).isEqualTo(root);
    }

    @Test
    void resolveEffectiveBuildRoot_withProjectRoot_returnsSubDirectory() throws Exception {
        Path root = Files.createTempDirectory(tempDir, "runtime-source-");
        Path nested = root.resolve("aitc-standard-springboot-api");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("pom.xml"), "<project/>");

        RuntimeDetectionResult detection = RuntimeDetectionResult.builder()
                .runtimeType(RuntimeType.SPRING_BOOT_MAVEN)
                .supported(true)
                .detectedPort(8080)
                .contextPath("/api")
                .projectRoot("aitc-standard-springboot-api")
                .build();

        Path result = orchestrator.resolveEffectiveBuildRoot(root, detection);

        assertThat(result).isEqualTo(nested);
    }

    @Test
    void start_nestedProjectRoot_usesSubDirectoryAsDockerBuildContext() throws Exception {
        // Set up materialized root with nested structure
        Path root = Files.createTempDirectory(tempDir, "runtime-source-");
        Path nested = root.resolve("aitc-standard-springboot-api");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("pom.xml"), "<project/>");

        MaterializedRuntimeSource mat = MaterializedRuntimeSource.builder()
                .projectId(project.getId())
                .rootDir(root)
                .materializedFiles(List.of("aitc-standard-springboot-api/pom.xml"))
                .build();
        when(materializer.materialize(project.getId())).thenReturn(mat);

        RuntimeDetectionResult detection = RuntimeDetectionResult.builder()
                .runtimeType(RuntimeType.SPRING_BOOT_MAVEN)
                .supported(true)
                .detectedPort(8080)
                .contextPath("/api")
                .projectRoot("aitc-standard-springboot-api")
                .build();

        orchestrator.start(project, detection);

        // docker build command context path must point to nested subdirectory, not root
        String nestedAbsolute = nested.toAbsolutePath().toString();
        assertThat(commands.commands.stream()
                .filter(c -> c.size() > 2 && c.get(1).equals("build"))
                .toList())
                .anySatisfy(c -> assertThat(c).contains(nestedAbsolute));
    }

    // ── Docker build process result handling (hotfix) ─────────────────────────

    @Test
    void dockerBuild_stderrWarningExitZero_isSuccess() throws Exception {
        // Orchestrator must mark runtime UP when docker build exits 0, even if stderr has content.
        // This covers the "DEPRECATED: The legacy builder" warning case.
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));

        // Override: build returns exitCode=0 but has non-empty stderr
        commands.stderrWarningOnBuild = true;

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(result.getLastError()).isNull();
    }

    @Test
    void dockerBuild_nonZeroExit_isBuildFailedWithExitCode() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        commands.failBuild = true; // returns exitCode=1

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(result.getLastError()).contains("docker build failed");
    }

    @Test
    void dockerBuild_timeoutKillsProcessAndMarksFailed() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        commands.buildTimedOut = true;

        SourceRuntime result = orchestrator.start(project, supportedMaven());

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(result.getLastError()).contains("docker build failed");
        assertThat(result.getLastError()).contains("timed out");
    }

    @Test
    void dockerBuild_usesConfiguredBuildTimeout() throws Exception {
        Path root = materializedRoot();
        when(materializer.materialize(project.getId())).thenReturn(materialized(root));
        properties.setBuildTimeoutSeconds(600);

        orchestrator.start(project, supportedMaven());

        // Check that the timeout passed to the executor for the build command equals 600
        assertThat(commands.capturedBuildTimeout).isEqualTo(600);
    }

    private static class RecordingCommandExecutor implements DockerRuntimeOrchestrator.CommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private boolean failBuild;
        private boolean failRun;
        private boolean stderrWarningOnBuild;
        private boolean buildTimedOut;
        int capturedBuildTimeout = -1;

        @Override
        public DockerRuntimeOrchestrator.CommandResult run(int timeoutSeconds, List<String> command) {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("docker", "network", "inspect", "prod-net"))) {
                return new DockerRuntimeOrchestrator.CommandResult(true, 0, "network exists");
            }
            if (command.size() > 2 && command.get(1).equals("build")) {
                capturedBuildTimeout = timeoutSeconds;
                if (failBuild) {
                    return new DockerRuntimeOrchestrator.CommandResult(false, 1,
                            "compile error", false, 500L, "compile error", "");
                }
                if (buildTimedOut) {
                    return new DockerRuntimeOrchestrator.CommandResult(false, -1,
                            "Process timed out after 300000ms (limit=300s). stdout=[] stderr=[]",
                            true, 300_000L, "", "");
                }
                if (stderrWarningOnBuild) {
                    // Simulate docker build with deprecation warning on stderr but exit 0
                    return new DockerRuntimeOrchestrator.CommandResult(true, 0,
                            "Successfully built abc123\n[stderr] DEPRECATED: The legacy builder is deprecated",
                            false, 1500L,
                            "Successfully built abc123",
                            "DEPRECATED: The legacy builder is deprecated and incompatible with Buildx.");
                }
            }
            if (command.size() > 2 && command.get(1).equals("run") && failRun) {
                return new DockerRuntimeOrchestrator.CommandResult(false, 1, "container crashed");
            }
            return new DockerRuntimeOrchestrator.CommandResult(true, 0, "ok");
        }
    }
}
