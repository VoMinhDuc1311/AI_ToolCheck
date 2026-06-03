package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeSourceMaterializerImplTest {

    private SourceFileRepository sourceFileRepository;
    private UUID projectId;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        sourceFileRepository = mock(SourceFileRepository.class);
        projectId = UUID.randomUUID();
    }

    @Test
    void materializesPomXmlAndJavaFilesWithRelativePaths() throws Exception {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(
                file("pom.xml", FileType.BUILD, "<project/>"),
                file("src/main/java/acme/App.java", FileType.APPLICATION, "class App {}"));

        try (MaterializedRuntimeSource source = service.materialize(projectId)) {
            assertThat(Files.readString(source.getRootDir().resolve("pom.xml"))).isEqualTo("<project/>");
            assertThat(Files.readString(source.getRootDir().resolve("src/main/java/acme/App.java"))).isEqualTo("class App {}");
            assertThat(source.getMaterializedFiles()).contains("pom.xml", "src/main/java/acme/App.java");
        }
    }

    @Test
    void materializesApplicationYamlInResourcesPath() throws Exception {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("src/main/resources/application.yml", FileType.APP_CONFIG, "server:\n  port: 8081\n"));

        try (MaterializedRuntimeSource source = service.materialize(projectId)) {
            assertThat(Files.readString(source.getRootDir().resolve("src/main/resources/application.yml")))
                    .isEqualTo("server:\n  port: 8081\n");
        }
    }

    @Test
    void materializesMavenWrapper() throws Exception {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("mvnw", FileType.SCRIPT, "#!/bin/sh\n"));

        try (MaterializedRuntimeSource source = service.materialize(projectId)) {
            assertThat(Files.exists(source.getRootDir().resolve("mvnw"))).isTrue();
        }
    }

    @Test
    void materializesGradleWrapper() throws Exception {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("gradlew", FileType.SCRIPT, "#!/bin/sh\n"));

        try (MaterializedRuntimeSource source = service.materialize(projectId)) {
            assertThat(Files.exists(source.getRootDir().resolve("gradlew"))).isTrue();
        }
    }

    @Test
    void materializesDockerfileIfPreserved() throws Exception {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("Dockerfile", FileType.BUILD, "FROM eclipse-temurin:21\n"));

        try (MaterializedRuntimeSource source = service.materialize(projectId)) {
            assertThat(Files.readString(source.getRootDir().resolve("Dockerfile")))
                    .isEqualTo("FROM eclipse-temurin:21\n");
        }
    }

    @Test
    void rejectsPathTraversal() {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("src/../pom.xml", FileType.BUILD, "<project/>"));

        assertThatThrownBy(() -> service.materialize(projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal");
    }

    @Test
    void rejectsAbsoluteUnixPath() {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("/pom.xml", FileType.BUILD, "<project/>"));

        assertThatThrownBy(() -> service.materialize(projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Absolute");
    }

    @Test
    void rejectsWindowsDrivePath() {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("C:\\repo\\pom.xml", FileType.BUILD, "<project/>"));

        assertThatThrownBy(() -> service.materialize(projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Windows drive");
    }

    @Test
    void rejectsLeadingBackslashPath() {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("\\src\\main\\java\\App.java", FileType.APPLICATION, "class App {}"));

        assertThatThrownBy(() -> service.materialize(projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Absolute");
    }

    @Test
    void rejectsNullBytePath() {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("src/main/java/App.java\0", FileType.APPLICATION, "class App {}"));

        assertThatThrownBy(() -> service.materialize(projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null byte");
    }

    @Test
    void writesUtf8ContentWithoutTrimming() throws Exception {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        String content = "  Xin chao\nclass App {}\n\n";
        stubFiles(file("src/main/java/App.java", FileType.APPLICATION, content));

        try (MaterializedRuntimeSource source = service.materialize(projectId)) {
            assertThat(Files.readString(source.getRootDir().resolve("src/main/java/App.java"))).isEqualTo(content);
        }
    }

    @Test
    void setsExecutableBitForMvnwOrGradlew_whenSupported() throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("mvnw", FileType.SCRIPT, "#!/bin/sh\n"));

        try (MaterializedRuntimeSource source = service.materialize(projectId)) {
            assertThat(Files.isExecutable(source.getRootDir().resolve("mvnw"))).isTrue();
        }
    }

    @Test
    void cleanupRemovesTempDirectory() {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(file("pom.xml", FileType.BUILD, "<project/>"));

        MaterializedRuntimeSource source = service.materialize(projectId);
        Path root = source.getRootDir();
        assertThat(Files.exists(root)).isTrue();

        source.cleanup();
        source.cleanup();

        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void failureDuringMaterializationCleansTempDirectory() {
        Path root = tempDir.resolve("runtime-root");
        TestMaterializer service = serviceAt(root);
        stubFiles(
                file("pom.xml", FileType.BUILD, "<project/>"),
                file("../outside.txt", FileType.BUILD, "bad"));

        assertThatThrownBy(() -> service.materialize(projectId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void duplicateRelativePath_rejectedOrHandledDeterministically() {
        TestMaterializer service = serviceAt(tempDir.resolve("runtime-root"));
        stubFiles(
                file("pom.xml", FileType.BUILD, "one"),
                file("pom.xml", FileType.BUILD, "two"));

        assertThatThrownBy(() -> service.materialize(projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    private TestMaterializer serviceAt(Path root) {
        return new TestMaterializer(sourceFileRepository, root);
    }

    private void stubFiles(SourceFile... files) {
        when(sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId)).thenReturn(List.of(files));
    }

    private SourceFile file(String path, FileType fileType, String content) {
        String normalized = path.replace("\\", "/");
        return SourceFile.builder()
                .filePath(path)
                .fileName(normalized.substring(normalized.lastIndexOf('/') + 1))
                .fileType(fileType)
                .sourceContent(content)
                .activeFlag(true)
                .deletedFlag(false)
                .build();
    }

    private static class TestMaterializer extends RuntimeSourceMaterializerImpl {
        private final Path root;

        TestMaterializer(SourceFileRepository sourceFileRepository, Path root) {
            super(sourceFileRepository);
            this.root = root;
        }

        @Override
        Path createRoot(UUID projectId) throws IOException {
            Files.createDirectories(root);
            return root.toAbsolutePath().normalize();
        }
    }
}
