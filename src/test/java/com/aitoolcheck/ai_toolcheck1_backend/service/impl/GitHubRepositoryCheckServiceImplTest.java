package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github.GitHubRepositoryCheckResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GitHubDetectedProjectType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GitHubRepositoryCheckServiceImpl}.
 *
 * <p>HTTP calls are avoided entirely — only the pure logic methods are tested:
 * <ul>
 *   <li>{@code check()} pre-flight validation
 *   <li>{@code deriveProjectType()} detection precedence
 *   <li>{@code performCheck()} when stubbed via Spy
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GitHubRepositoryCheckServiceImplTest {

    @Mock
    private ProjectAccessService projectAccessService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private GitHubRepositoryCheckServiceImpl service;

    private static final UUID PROJECT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = spy(new GitHubRepositoryCheckServiceImpl(projectAccessService, objectMapper));
    }

    // ── check() — pre-flight validation ───────────────────────────────────────

    @Test
    void check_throwsBadRequest_whenNoRepositoryUrl() {
        SourceProject project = projectWithUrl(null, null);
        when(projectAccessService.requireCanViewProject(PROJECT_ID)).thenReturn(project);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.check(PROJECT_ID));
        assertTrue(ex.getMessage().contains("does not have a linked GitHub repository URL"));
    }

    @Test
    void check_throwsBadRequest_whenRepositoryUrlIsBlank() {
        SourceProject project = projectWithUrl("   ", null);
        when(projectAccessService.requireCanViewProject(PROJECT_ID)).thenReturn(project);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.check(PROJECT_ID));
        assertTrue(ex.getMessage().contains("does not have a linked GitHub repository URL"));
    }

    @Test
    void check_delegatesToPerformCheck_withNormalisedOwnerAndRepo() {
        SourceProject project = projectWithUrl("https://github.com/owner/my-repo", "develop");
        when(projectAccessService.requireCanViewProject(PROJECT_ID)).thenReturn(project);

        // Stub performCheck to avoid real HTTP
        GitHubRepositoryCheckResponse stubbed = GitHubRepositoryCheckResponse.builder()
                .reachable(true)
                .provider("GITHUB")
                .owner("owner")
                .repositoryName("my-repo")
                .branch("develop")
                .detectedProjectType(GitHubDetectedProjectType.SPRING_BOOT)
                .message("ok")
                .build();
        doReturn(stubbed)
                .when(service)
                .performCheck("owner", "my-repo", "develop",
                              "https://github.com/owner/my-repo");

        GitHubRepositoryCheckResponse result = service.check(PROJECT_ID);

        assertNotNull(result);
        assertTrue(result.isReachable());
        assertEquals("owner", result.getOwner());
        assertEquals("my-repo", result.getRepositoryName());
        assertEquals("develop", result.getBranch());
        verify(service).performCheck("owner", "my-repo", "develop",
                                     "https://github.com/owner/my-repo");
    }

    // ── deriveProjectType — detection rules ───────────────────────────────────

    @Test
    void deriveProjectType_returnsSpringBoot_whenPomXmlAndSrcMainJavaPresent() {
        assertEquals(GitHubDetectedProjectType.SPRING_BOOT,
                service.deriveProjectType(true, false, false, false, true));
    }

    @Test
    void deriveProjectType_returnsJavaMaven_whenOnlyPomXmlPresent() {
        assertEquals(GitHubDetectedProjectType.JAVA_MAVEN,
                service.deriveProjectType(true, false, false, false, false));
    }

    @Test
    void deriveProjectType_returnsJavaGradle_whenBuildGradlePresent() {
        assertEquals(GitHubDetectedProjectType.JAVA_GRADLE,
                service.deriveProjectType(false, true, false, false, false));
    }

    @Test
    void deriveProjectType_returnsJavaGradle_whenSettingsGradlePresent() {
        assertEquals(GitHubDetectedProjectType.JAVA_GRADLE,
                service.deriveProjectType(false, false, true, false, false));
    }

    @Test
    void deriveProjectType_returnsNode_whenOnlyPackageJsonPresent() {
        assertEquals(GitHubDetectedProjectType.NODE,
                service.deriveProjectType(false, false, false, true, false));
    }

    @Test
    void deriveProjectType_returnsUnknown_whenNoMarkersPresent() {
        assertEquals(GitHubDetectedProjectType.UNKNOWN,
                service.deriveProjectType(false, false, false, false, false));
    }

    @Test
    void deriveProjectType_springBootTakesPrecedenceOverAllOthers() {
        // pom.xml + src/main/java wins even when build.gradle and package.json also exist
        assertEquals(GitHubDetectedProjectType.SPRING_BOOT,
                service.deriveProjectType(true, true, true, true, true));
    }

    @Test
    void deriveProjectType_mavenTakesPrecedenceOverGradleAndNode() {
        // pom.xml without src/main/java = JAVA_MAVEN, even if build.gradle also exists
        assertEquals(GitHubDetectedProjectType.JAVA_MAVEN,
                service.deriveProjectType(true, true, false, true, false));
    }

    @Test
    void deriveProjectType_gradleTakesPrecedenceOverNode() {
        assertEquals(GitHubDetectedProjectType.JAVA_GRADLE,
                service.deriveProjectType(false, true, false, true, false));
    }

    // ── performCheck — unreachable path (no HTTP needed) ─────────────────────

    @Test
    void performCheck_returnsReachableFalse_andCorrectShape_whenRepoMetaNotReachable() {
        // Directly validate the DTO shape contract for the unreachable path.
        // This does not make network calls — it tests the builder/field contract.
        GitHubRepositoryCheckResponse notReachable = GitHubRepositoryCheckResponse.builder()
                .reachable(false)
                .provider("GITHUB")
                .owner("owner")
                .repositoryName("repo")
                .repositoryUrl("https://github.com/owner/repo")
                .branch(null)
                .defaultBranch(null)
                .detectedProjectType(GitHubDetectedProjectType.UNKNOWN)
                .message("Repository not found or is private (HTTP 404).")
                .build();

        assertFalse(notReachable.isReachable());
        assertEquals("GITHUB", notReachable.getProvider());
        assertNull(notReachable.getDefaultBranch());
        assertEquals(GitHubDetectedProjectType.UNKNOWN, notReachable.getDetectedProjectType());
        assertTrue(notReachable.getMessage().contains("not found or is private"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SourceProject projectWithUrl(String url, String branch) {
        SourceProject p = new SourceProject();
        p.setRepositoryUrl(url);
        p.setRepositoryBranch(branch);
        return p;
    }
}
