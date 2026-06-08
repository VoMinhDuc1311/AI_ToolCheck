package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeDetectorServiceImplTest {

    private SourceFileRepository sourceFileRepository;
    private RuntimeDetectorServiceImpl service;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        sourceFileRepository = mock(SourceFileRepository.class);
        service = new RuntimeDetectorServiceImpl(sourceFileRepository);
        projectId = UUID.randomUUID();
    }

    @Test
    void detectSpringBootMavenSource_whenPomContainsSpringBootStarter_returnsSpringBootMaven() {
        RuntimeDetectionResult result = detect(file("pom.xml", FileType.BUILD,
                "<dependency><artifactId>spring-boot-starter-web</artifactId></dependency>"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_MAVEN);
        assertThat(result.getBuildFilePath()).isEqualTo("pom.xml");
    }

    @Test
    void detectSpringBootMavenSource_whenPomContainsSpringBootMavenPlugin_returnsSpringBootMaven() {
        RuntimeDetectionResult result = detect(file("pom.xml", FileType.BUILD,
                "<artifactId>spring-boot-maven-plugin</artifactId>"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_MAVEN);
    }

    @Test
    void mavenPomWithoutSpringBoot_returnsUnsupported() {
        RuntimeDetectionResult result = detect(file("pom.xml", FileType.BUILD,
                "<dependency><groupId>junit</groupId></dependency>"));

        assertThat(result.isSupported()).isFalse();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.UNSUPPORTED);
        assertThat(result.getMessage()).contains("Maven project found");
    }

    @Test
    void detectSpringBootGradleSource_whenBuildGradleHasBootPlugin_returnsSpringBootGradle() {
        RuntimeDetectionResult result = detect(file("build.gradle", FileType.BUILD,
                "plugins { id 'org.springframework.boot' version '3.2.0' }"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_GRADLE);
    }

    @Test
    void detectSpringBootGradleKtsSource_whenBuildGradleKtsHasBootPlugin_returnsSpringBootGradle() {
        RuntimeDetectionResult result = detect(file("build.gradle.kts", FileType.BUILD,
                "plugins { id(\"org.springframework.boot\") version \"3.2.0\" }"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_GRADLE);
    }

    @Test
    void gradleWithoutSpringBoot_returnsUnsupported() {
        RuntimeDetectionResult result = detect(file("build.gradle", FileType.BUILD,
                "plugins { id 'java' }"));

        assertThat(result.isSupported()).isFalse();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.UNSUPPORTED);
        assertThat(result.getMessage()).contains("Gradle project found");
    }

    @Test
    void unsupportedSource_withoutBuildFile_returnsClearUnsupported() {
        RuntimeDetectionResult result = detect(file("src/main/java/acme/App.java", FileType.APPLICATION, "class App {}"));

        assertThat(result.isSupported()).isFalse();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.UNSUPPORTED);
        assertThat(result.getMessage()).contains("No supported Spring Boot build file found.");
    }

    @Test
    void detectContextPath_fromApplicationYaml() {
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("src/main/resources/application.yml", FileType.APP_CONFIG,
                        "server:\n  servlet:\n    context-path: /api\n"));

        assertThat(result.getContextPath()).isEqualTo("/api");
        assertThat(result.getConfigFilePath()).isEqualTo("src/main/resources/application.yml");
    }

    @Test
    void detectContextPath_fromApplicationProperties() {
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("src/main/resources/application.properties", FileType.APP_CONFIG,
                        "server.servlet.context-path=/internal"));

        assertThat(result.getContextPath()).isEqualTo("/internal");
    }

    @Test
    void normalizeContextPath_addsLeadingSlash() {
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("application.yaml", FileType.APP_CONFIG,
                        "server:\n  servlet:\n    context-path: api/\n"));

        assertThat(result.getContextPath()).isEqualTo("/api");
    }

    @Test
    void normalizeContextPath_rootBecomesNull() {
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("application.properties", FileType.APP_CONFIG,
                        "server.servlet.context-path=/"));

        assertThat(result.getContextPath()).isNull();
    }

    @Test
    void detectServerPort_fromYaml() {
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("src/main/resources/application.yml", FileType.APP_CONFIG,
                        "server:\n  port: 8081\n"));

        assertThat(result.getDetectedPort()).isEqualTo(8081);
    }

    @Test
    void detectServerPort_fromProperties() {
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("application.properties", FileType.APP_CONFIG,
                        "server.port=9090"));

        assertThat(result.getDetectedPort()).isEqualTo(9090);
    }

    @Test
    void invalidServerPort_doesNotCrash() {
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("application.properties", FileType.APP_CONFIG,
                        "server.port=${PORT:8080}"));

        assertThat(result.getDetectedPort()).isNull();
    }

    @Test
    void windowsPathSeparators_areHandled() {
        RuntimeDetectionResult result = detect(
                file(".\\pom.xml", FileType.BUILD, "org.springframework.boot"),
                file("src\\main\\resources\\application.yml", FileType.APP_CONFIG,
                        "server:\n  servlet:\n    context-path: api\n"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_MAVEN);
        assertThat(result.getContextPath()).isEqualTo("/api");
    }

    @Test
    void mavenNonBootButGradleBoot_choosesGradle() {
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "<groupId>plain</groupId>"),
                file("build.gradle", FileType.BUILD, "implementation 'org.springframework.boot:spring-boot-starter-web'"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_GRADLE);
    }

    private RuntimeDetectionResult detect(SourceFile... files) {
        when(sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId)).thenReturn(List.of(files));
        return service.detect(projectId);
    }

    private SourceFile file(String path, FileType fileType, String content) {
        return SourceFile.builder()
                .filePath(path)
                .fileName(path.substring(path.replace("\\", "/").lastIndexOf('/') + 1))
                .fileType(fileType)
                .sourceContent(content)
                .activeFlag(true)
                .deletedFlag(false)
                .build();
    }

    // ── New tests: nested source root detection (hotfix) ──────────────────────

    @Test
    void rootPom_detectsRuntimeProjectRoot_asNull() {
        // Root-level pom.xml — no prefix, projectRoot must be null
        RuntimeDetectionResult result = detect(
                file("pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("src/main/java/App.java", FileType.APPLICATION, "class App {}"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_MAVEN);
        assertThat(result.getProjectRoot()).isNull();
    }

    @Test
    void nestedSingleFolderPom_detectsRuntimeProjectRoot() {
        // All paths under "aitc-standard-springboot-api/" — detector must strip prefix and detect Maven
        RuntimeDetectionResult result = detect(
                file("aitc-standard-springboot-api/pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("aitc-standard-springboot-api/src/main/java/App.java", FileType.APPLICATION, "class App {}"),
                file("aitc-standard-springboot-api/Dockerfile", FileType.BUILD, "FROM eclipse-temurin:21"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_MAVEN);
        assertThat(result.getProjectRoot()).isEqualTo("aitc-standard-springboot-api");
        assertThat(result.getBuildFilePath()).isEqualTo("pom.xml"); // stripped path
    }

    @Test
    void nestedSingleFolderDockerfile_detectsRuntimeProjectRoot() {
        // No pom.xml, but Dockerfile with Spring evidence inside a nested folder
        // Detection currently relies on gradle/pom; Dockerfile alone = unsupported type.
        // This test verifies projectRoot is still correctly detected.
        RuntimeDetectionResult result = detect(
                file("myapp/Dockerfile", FileType.BUILD, "FROM eclipse-temurin:21-jre"),
                file("myapp/src/main/java/App.java", FileType.APPLICATION, "class App {}"));

        // Dockerfile alone without pom/gradle is UNSUPPORTED in current detection logic,
        // but projectRoot MUST still be populated correctly.
        assertThat(result.getProjectRoot()).isEqualTo("myapp");
    }

    @Test
    void rootDockerfile_stillWorks() {
        // Flat layout: Dockerfile at root, no prefix expected
        RuntimeDetectionResult result = detect(
                file("Dockerfile", FileType.BUILD, "FROM eclipse-temurin:21-jre"),
                file("src/main/java/App.java", FileType.APPLICATION, "class App {}"));

        // pom/gradle not present → UNSUPPORTED, but projectRoot must be null (root layout)
        assertThat(result.getProjectRoot()).isNull();
    }

    @Test
    void noBuildFile_returnsUnsupportedWithClearError() {
        RuntimeDetectionResult result = detect(
                file("src/main/java/App.java", FileType.APPLICATION, "class App {}"),
                file("src/main/resources/application.yml", FileType.APP_CONFIG, "server:\n  port: 8080\n"));

        assertThat(result.isSupported()).isFalse();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.UNSUPPORTED);
        assertThat(result.getMessage()).contains("No supported Spring Boot build file found.");
        assertThat(result.getMessage()).contains("Checked root and nested directories");
    }

    @Test
    void multipleBuildRoots_returnsClearAmbiguousError() {
        // Two different top-level folders each containing a pom.xml
        RuntimeDetectionResult result = detect(
                file("service-a/pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("service-a/src/main/java/A.java", FileType.APPLICATION, "class A {}"),
                file("service-b/pom.xml", FileType.BUILD, "spring-boot-starter"),
                file("service-b/src/main/java/B.java", FileType.APPLICATION, "class B {}"));

        assertThat(result.isSupported()).isFalse();
        assertThat(result.getMessage()).contains("Multiple potential Spring Boot build roots found");
        assertThat(result.getMessage()).contains("service-a");
        assertThat(result.getMessage()).contains("service-b");
    }

    @Test
    void nestedSingleFolderGradle_detectsRuntimeProjectRoot() {
        RuntimeDetectionResult result = detect(
                file("my-gradle-app/build.gradle", FileType.BUILD, "id 'org.springframework.boot' version '3.2.0'"),
                file("my-gradle-app/src/main/java/App.java", FileType.APPLICATION, "class App {}"));

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_GRADLE);
        assertThat(result.getProjectRoot()).isEqualTo("my-gradle-app");
    }

    @Test
    void detectCommonTopLevelPrefix_singleFileAtRoot_returnsNull() {
        List<SourceFile> files = List.of(file("pom.xml", FileType.BUILD, ""));
        assertThat(service.detectCommonTopLevelPrefix(files)).isNull();
    }

    @Test
    void detectCommonTopLevelPrefix_allNestedUnderSameFolder_returnsPrefix() {
        List<SourceFile> files = List.of(
                file("project/pom.xml", FileType.BUILD, ""),
                file("project/src/main/java/App.java", FileType.APPLICATION, ""));
        assertThat(service.detectCommonTopLevelPrefix(files)).isEqualTo("project");
    }

    @Test
    void detectCommonTopLevelPrefix_mixedFolders_returnsNull() {
        List<SourceFile> files = List.of(
                file("service-a/pom.xml", FileType.BUILD, ""),
                file("service-b/pom.xml", FileType.BUILD, ""));
        assertThat(service.detectCommonTopLevelPrefix(files)).isNull();
    }
}
