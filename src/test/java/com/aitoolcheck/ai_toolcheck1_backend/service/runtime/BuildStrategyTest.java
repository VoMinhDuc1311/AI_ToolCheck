package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DockerfileSource;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
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
import static org.mockito.Mockito.when;

/**
 * Tests for BuildStrategy selection and Generated Dockerfile correctness.
 */
class BuildStrategyTest {

    private SourceRuntimeRepository runtimeRepository;
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
    void setUp() throws Exception {
        runtimeRepository = mock(SourceRuntimeRepository.class);
        ApiEndpointRepository endpointRepository = mock(ApiEndpointRepository.class);
        materializer = mock(RuntimeSourceMaterializer.class);
        lifecycleService = mock(com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeLifecycleService.class);
        runtimeStartWorker = mock(RuntimeStartWorker.class);

        properties = new RuntimeAutoProperties();
        properties.setEnabled(true);
        properties.getDocker().setEnabled(true);
        properties.getDocker().setNetwork("prod-net");
        properties.setContainerPrefix("aitc-runtime");
        properties.setPublicHost("localhost");
        properties.setPortMin(18080);
        properties.setPortMax(18080);
        properties.setStartTimeoutSeconds(1);
        properties.setBuildTimeoutSeconds(300);

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

        // Create a minimal materialized structure with pom.xml + Dockerfile
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, "<project><dependencies><dependency><groupId>org.springframework.boot</groupId></dependency></dependencies></project>");
    }

    private MaterializedRuntimeSource materialized() {
        return MaterializedRuntimeSource.builder()
                .projectId(project.getId())
                .rootDir(tempDir)
                .build();
    }

    private RuntimeDetectionResult mavenDetection() {
        return RuntimeDetectionResult.builder()
                .runtimeType(RuntimeType.SPRING_BOOT_MAVEN)
                .supported(true)
                .message("ok")
                .detectedPort(8080)
                .build();
    }

    private RuntimeDetectionResult gradleDetection() {
        return RuntimeDetectionResult.builder()
                .runtimeType(RuntimeType.SPRING_BOOT_GRADLE)
                .supported(true)
                .message("ok")
                .detectedPort(8080)
                .build();
    }

    // ── BuildStrategy selection ───────────────────────────────────────────────

    @Test
    void auto_withUploadedDockerfile_usesUploadedDockerfile() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM eclipse-temurin:21-jre");
        when(materializer.materialize(project.getId())).thenReturn(materialized());

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.AUTO);

        assertThat(result.getDockerfileSource()).isEqualTo(DockerfileSource.UPLOADED);
        assertThat(result.getBuildStrategyRequested()).isEqualTo(BuildStrategy.AUTO);
        assertThat(commands.buildDockerfileArg).contains("Dockerfile");
    }

    @Test
    void auto_withoutUploadedDockerfile_usesGeneratedDockerfile() throws Exception {
        // No Dockerfile in tempDir
        when(materializer.materialize(project.getId())).thenReturn(materialized());

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.AUTO);

        assertThat(result.getDockerfileSource()).isEqualTo(DockerfileSource.GENERATED);
        assertThat(commands.buildDockerfileArg).contains("Dockerfile.autorun");
    }

    @Test
    void uploadedOnly_withoutDockerfile_failsClearly() throws Exception {
        // No Dockerfile in tempDir
        when(materializer.materialize(project.getId())).thenReturn(materialized());

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.UPLOADED_DOCKERFILE_ONLY);

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(result.getLastError()).contains("UPLOADED_DOCKERFILE_ONLY");
        assertThat(result.getLastError()).contains("no Dockerfile found");
    }

    @Test
    void uploadedOnly_withDockerfile_usesUploadedDockerfile() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM eclipse-temurin:21-jre");
        when(materializer.materialize(project.getId())).thenReturn(materialized());

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.UPLOADED_DOCKERFILE_ONLY);

        assertThat(result.getDockerfileSource()).isEqualTo(DockerfileSource.UPLOADED);
    }

    @Test
    void generatedDockerfile_ignoresUploadedDockerfile() throws Exception {
        // Even with a Dockerfile present, GENERATED_DOCKERFILE must ignore it
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM ubuntu"); // wrong/bad Dockerfile
        when(materializer.materialize(project.getId())).thenReturn(materialized());

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.GENERATED_DOCKERFILE);

        assertThat(result.getDockerfileSource()).isEqualTo(DockerfileSource.GENERATED);
        assertThat(commands.buildDockerfileArg).contains("Dockerfile.autorun");
    }

    @Test
    void autoWithFallback_uploadedFails_generatedSucceeds_marksUpAndRecordsFallback() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM bad-image");
        when(materializer.materialize(project.getId())).thenReturn(materialized());
        commands.failFirstBuild = true; // First build (uploaded) fails; second (generated) succeeds

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.AUTO_WITH_FALLBACK);

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(result.getDockerfileSource()).isEqualTo(DockerfileSource.GENERATED);
        assertThat(result.getFallbackReason()).contains("Uploaded Dockerfile failed");
        assertThat(result.getBuildStrategyUsed()).isEqualTo(BuildStrategy.AUTO_WITH_FALLBACK);
    }

    @Test
    void autoWithFallback_uploadedFails_generatedSucceeds_preservesOriginalFallbackReason() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM bad-image");
        when(materializer.materialize(project.getId())).thenReturn(materialized());
        commands.failFirstBuild = true;

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.AUTO_WITH_FALLBACK);

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.UP);
        assertThat(result.getFallbackReason()).contains("Uploaded Dockerfile failed: exitCode=127");
        assertThat(result.getFallbackReason()).contains("/bin/sh: 1: mvn: not found");
        assertThat(result.getLastError()).isNull();
    }

    @Test
    void autoWithFallback_bothFail_reportsBothErrors() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM bad-image");
        when(materializer.materialize(project.getId())).thenReturn(materialized());
        commands.failBuild = true;

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.AUTO_WITH_FALLBACK);

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(result.getLastError()).contains("Uploaded Dockerfile failed");
        assertThat(result.getLastError()).contains("Generated Dockerfile failed");
    }

    @Test
    void auto_uploadedFails_doesNotFallback() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM bad-image");
        when(materializer.materialize(project.getId())).thenReturn(materialized());
        commands.failBuild = true;

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.AUTO);

        assertThat(result.getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_FAILED);
        assertThat(result.getLastError()).contains("Uploaded Dockerfile failed");
        assertThat(result.getLastError()).contains("GENERATED_DOCKERFILE");
        // Must not have attempted a second build
        assertThat(commands.buildCount).isEqualTo(1);
    }

    @Test
    void startRuntime_withoutBody_defaultsToAuto() throws Exception {
        // start(project, detection) delegates to AUTO
        when(materializer.materialize(project.getId())).thenReturn(materialized());

        SourceRuntime result = orchestrator.start(project, mavenDetection()); // no-arg = AUTO

        assertThat(result.getBuildStrategyRequested()).isEqualTo(BuildStrategy.AUTO);
    }

    // ── Generated Dockerfile correctness ─────────────────────────────────────

    @Test
    void generateMavenDockerfile_withoutMvnw_usesMavenBuilderImage() {
        String dockerfile = orchestrator.generateDockerfile(mavenDetection(), 8080, false, false);
        assertThat(dockerfile).contains("FROM maven:3.9-eclipse-temurin-21 AS build");
        assertThat(dockerfile).contains("RUN mvn -q -DskipTests clean package");
    }

    @Test
    void generatedMavenDockerfile_doesNotUseMvnOnPlainJdkImage() {
        // Without mvnw, must NOT have eclipse-temurin:21-jdk as build image calling mvn
        String dockerfile = orchestrator.generateDockerfile(mavenDetection(), 8080, false, false);
        // The build stage must NOT be eclipse-temurin:21-jdk
        String buildStage = dockerfile.lines()
                .filter(l -> l.startsWith("FROM") && l.contains("AS build"))
                .findFirst().orElse("");
        assertThat(buildStage).doesNotContain("eclipse-temurin:21-jdk");
        assertThat(buildStage).contains("maven:");
    }

    @Test
    void generateMavenDockerfile_withMvnw_usesMavenWrapper() {
        String dockerfile = orchestrator.generateDockerfile(mavenDetection(), 8080, true, false);
        assertThat(dockerfile).contains("FROM eclipse-temurin:21-jdk AS build");
        assertThat(dockerfile).contains("COPY mvnw .");
        assertThat(dockerfile).contains("RUN chmod +x mvnw");
        assertThat(dockerfile).contains("RUN ./mvnw -q -DskipTests clean package");
        // Must NOT call mvn CLI (only ./mvnw)
        assertThat(dockerfile).doesNotContain("RUN mvn ");
    }

    @Test
    void generateGradleDockerfile_withoutGradlew_usesGradleBuilderImage() {
        String dockerfile = orchestrator.generateDockerfile(gradleDetection(), 8080, false, false);
        assertThat(dockerfile).contains("FROM gradle:8-jdk21 AS build");
        assertThat(dockerfile).contains("RUN gradle -q -x test bootJar");
    }

    @Test
    void generateGradleDockerfile_withGradlew_usesWrapper() {
        String dockerfile = orchestrator.generateDockerfile(gradleDetection(), 8080, false, true);
        assertThat(dockerfile).contains("FROM eclipse-temurin:21-jdk AS build");
        assertThat(dockerfile).contains("COPY gradlew .");
        assertThat(dockerfile).contains("RUN chmod +x gradlew");
        assertThat(dockerfile).contains("RUN ./gradlew -q -x test bootJar");
    }

    @Test
    void generatedDockerfile_exposesDetectedPort() {
        String dockerfile = orchestrator.generateDockerfile(mavenDetection(), 9090, false, false);
        assertThat(dockerfile).contains("EXPOSE 9090");
        assertThat(dockerfile).contains("SERVER_PORT=9090");
    }

    // ── sourceRuntimeResponse_containsBuildStrategyMetadata ─────────────────

    @Test
    void sourceRuntime_recordsBuildStrategyMetadata() throws Exception {
        when(materializer.materialize(project.getId())).thenReturn(materialized());

        SourceRuntime result = orchestrator.start(project, mavenDetection(), BuildStrategy.GENERATED_DOCKERFILE);

        assertThat(result.getBuildStrategyRequested()).isEqualTo(BuildStrategy.GENERATED_DOCKERFILE);
        assertThat(result.getBuildStrategyUsed()).isEqualTo(BuildStrategy.GENERATED_DOCKERFILE);
        assertThat(result.getDockerfileSource()).isEqualTo(DockerfileSource.GENERATED);
    }

    // ── selectDockerfile unit tests ──────────────────────────────────────────

    @Test
    void selectDockerfile_auto_noUploaded_returnsGenerated() throws Exception {
        DockerRuntimeOrchestrator.DockerBuildPlan plan =
                orchestrator.selectDockerfile(tempDir, mavenDetection(), 8080, BuildStrategy.AUTO);
        assertThat(plan.source()).isEqualTo(DockerfileSource.GENERATED);
    }

    @Test
    void selectDockerfile_auto_withUploaded_returnsUploaded() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM scratch");
        DockerRuntimeOrchestrator.DockerBuildPlan plan =
                orchestrator.selectDockerfile(tempDir, mavenDetection(), 8080, BuildStrategy.AUTO);
        assertThat(plan.source()).isEqualTo(DockerfileSource.UPLOADED);
    }

    @Test
    void selectDockerfile_generated_alwaysGenerates() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM scratch"); // ignored
        DockerRuntimeOrchestrator.DockerBuildPlan plan =
                orchestrator.selectDockerfile(tempDir, mavenDetection(), 8080, BuildStrategy.GENERATED_DOCKERFILE);
        assertThat(plan.source()).isEqualTo(DockerfileSource.GENERATED);
    }

    // ── Helper: RecordingCommandExecutor ─────────────────────────────────────

    private static class RecordingCommandExecutor implements DockerRuntimeOrchestrator.CommandExecutor {
        final List<List<String>> allCommands = new ArrayList<>();
        boolean failBuild = false;
        boolean failFirstBuild = false;
        int buildCount = 0;
        String buildDockerfileArg = null;

        @Override
        public DockerRuntimeOrchestrator.CommandResult run(int timeoutSeconds, List<String> command) {
            allCommands.add(List.copyOf(command));
            if (command.equals(List.of("docker", "network", "inspect", "prod-net"))) {
                return new DockerRuntimeOrchestrator.CommandResult(true, 0, "ok");
            }
            if (command.size() > 2 && command.get(1).equals("build")) {
                buildCount++;
                // -f <dockerfilePath> is args 2 and 3
                if (command.size() > 3) {
                    buildDockerfileArg = command.get(3);
                }
                if (failBuild) {
                    return new DockerRuntimeOrchestrator.CommandResult(false, 127,
                            "/bin/sh: 1: mvn: not found", false, 500L,
                            "/bin/sh: 1: mvn: not found", "");
                }
                if (failFirstBuild && buildCount == 1) {
                    return new DockerRuntimeOrchestrator.CommandResult(false, 127,
                            "/bin/sh: 1: mvn: not found", false, 500L,
                            "/bin/sh: 1: mvn: not found", "");
                }
                return new DockerRuntimeOrchestrator.CommandResult(true, 0, "Successfully built");
            }
            if (command.size() > 2 && command.get(1).equals("run")) {
                return new DockerRuntimeOrchestrator.CommandResult(true, 0, "ok");
            }
            return new DockerRuntimeOrchestrator.CommandResult(true, 0, "ok");
        }
    }
}
