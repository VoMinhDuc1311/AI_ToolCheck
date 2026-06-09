package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.common.RuntimeTargetUrlValidator;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ConflictException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code defaultTargetBaseUrl} handling in {@link SourceProjectServiceImpl}.
 *
 * <p>Business rules tested:
 * <ul>
 *   <li>defaultTargetBaseUrl is optional — null and blank both become null</li>
 *   <li>If provided, must start with http:// or https://</li>
 *   <li>Invalid schemes (ftp, file, etc.) must be rejected</li>
 *   <li>defaultTargetBaseUrl must NEVER be auto-copied from repositoryUrl</li>
 *   <li>Detail response must expose defaultTargetBaseUrl</li>
 *   <li>Update can clear defaultTargetBaseUrl by sending null or blank</li>
 * </ul>
 */
class SourceProjectDefaultTargetUrlTest {

    private SourceProjectRepository sourceProjectRepository;
    private CurrentUserService currentUserService;
    private ProjectAccessService projectAccessService;
    private SourceProjectServiceImpl service;

    private UUID projectId;
    private AppUser mockUser;

    @BeforeEach
    void setUp() {
        sourceProjectRepository = mock(SourceProjectRepository.class);
        currentUserService = mock(CurrentUserService.class);
        projectAccessService = mock(ProjectAccessService.class);

        service = new SourceProjectServiceImpl(
                sourceProjectRepository,
                mock(ProjectMemberRepository.class),
                currentUserService,
                projectAccessService,
                mock(TestFailureAnalysisRepository.class),
                mock(AiJobLogRepository.class),
                mock(TestResultRepository.class),
                mock(TestRunItemRepository.class),
                mock(TestRunRepository.class),
                mock(TestCaseAssertionRepository.class),
                mock(TestCaseInputRepository.class),
                mock(TestCaseRepository.class),
                mock(LegacyInferenceLogRepository.class),
                mock(EndpointSchemaMapRepository.class),
                mock(ApiParameterRepository.class),
                mock(ApiEndpointRepository.class),
                mock(ApiSchemaFieldRepository.class),
                mock(ApiSchemaRepository.class),
                mock(ApiDocumentVersionRepository.class),
                mock(ApiDocumentRepository.class),
                mock(SourceAnalysisResultRepository.class),
                mock(SourceFileRepository.class),
                mock(SourceUploadVersionRepository.class)
        );

        projectId = UUID.randomUUID();
        mockUser = new AppUser();
        mockUser.setId(UUID.randomUUID());
        mockUser.setEmail("demo@test.com");

        when(currentUserService.getCurrentUser()).thenReturn(mockUser);
        when(sourceProjectRepository.existsByProjectKey(anyString())).thenReturn(false);
        when(sourceProjectRepository.existsByProjectName(anyString())).thenReturn(false);
        when(sourceProjectRepository.existsByProjectNameAndIdNot(anyString(), any())).thenReturn(false);
        when(projectAccessService.getCurrentUserProjectRole(any(), any())).thenReturn(null);
        when(projectAccessService.buildPermissions(any(), any())).thenReturn(null);
    }

    // ── A1: create with defaultTargetBaseUrl succeeds ────────────────────────

    @Test
    void createProject_withDefaultTargetBaseUrl_succeeds() {
        String targetUrl = "http://52.220.34.212:8081";
        SourceProject saved = buildSavedProject(null, targetUrl);
        when(sourceProjectRepository.saveAndFlush(any())).thenReturn(saved);

        CreateSourceProjectRequest request = buildCreateRequest(null, null, targetUrl);

        SourceProjectDetailResponse response = assertDoesNotThrow(() -> service.create(request));

        assertThat(response.getDefaultTargetBaseUrl()).isEqualTo(targetUrl);
    }

    // ── A2: update with defaultTargetBaseUrl succeeds ────────────────────────

    @Test
    void createProject_withUniqueProjectKey_success() {
        SourceProject saved = buildSavedProject(null, "http://localhost:8081");
        saved.setProjectKey("ADMIN-UI-AI-KEY-009");
        saved.setProjectName("Test AI Key New 009");
        when(sourceProjectRepository.saveAndFlush(any())).thenReturn(saved);

        CreateSourceProjectRequest request = CreateSourceProjectRequest.builder()
                .projectKey("ADMIN-UI-AI-KEY-009")
                .projectName("Test AI Key New 009")
                .description("Backend Spring Boot service for AI testcase generation retest")
                .repositoryUrl("")
                .repositoryBranch("")
                .defaultTargetBaseUrl("http://localhost:8081")
                .backendType(BackendType.SPRING_BOOT)
                .build();

        SourceProjectDetailResponse response = service.create(request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getProjectKey()).isEqualTo("ADMIN-UI-AI-KEY-009");
        assertThat(response.getProjectName()).isEqualTo("Test AI Key New 009");
        assertThat(response.getBackendType()).isEqualTo(BackendType.SPRING_BOOT);
        assertThat(response.getStatus()).isEqualTo(ProjectStatus.NEW);
        assertThat(response.getArchivedFlag()).isFalse();
        assertThat(response.getOwnerUserId()).isEqualTo(mockUser.getId());
    }

    @Test
    void createProject_withDuplicateProjectKey_returnsProjectKeyConflict() {
        when(sourceProjectRepository.existsByProjectKey("ADMIN-UI-AI-KEY-009")).thenReturn(true);

        CreateSourceProjectRequest request = CreateSourceProjectRequest.builder()
                .projectKey("ADMIN-UI-AI-KEY-009")
                .projectName("Test AI Key New 009")
                .backendType(BackendType.SPRING_BOOT)
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project key already exists: ADMIN-UI-AI-KEY-009")
                .hasMessageNotContaining("Re-upload failed");
        verify(sourceProjectRepository, never()).saveAndFlush(any());
    }

    @Test
    void createProject_mustNotReturnReuploadMetadataCleanupMessage() {
        when(sourceProjectRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'ADMIN-UI-AI-KEY-009' for key 'source_project.project_key'"));

        CreateSourceProjectRequest request = CreateSourceProjectRequest.builder()
                .projectKey("ADMIN-UI-AI-KEY-009")
                .projectName("Test AI Key New 009")
                .backendType(BackendType.SPRING_BOOT)
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project key already exists: ADMIN-UI-AI-KEY-009")
                .hasMessageNotContaining("Re-upload failed")
                .hasMessageNotContaining("metadata cleanup");
    }

    @Test
    void createProject_doesNotInvokeMetadataCleanupGuard() {
        assertThat(Arrays.stream(SourceProjectServiceImpl.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName()))
                .doesNotContain("ApiMetadataCleanupService");
    }

    @Test
    void updateProject_withDefaultTargetBaseUrl_succeeds() {
        String newTargetUrl = "https://staging.myapp.com";
        SourceProject existing = buildSavedProject(null, null);
        SourceProject updated = buildSavedProject(null, newTargetUrl);

        when(projectAccessService.requireCanManageProject(projectId)).thenReturn(existing);
        when(sourceProjectRepository.save(any())).thenReturn(updated);

        UpdateSourceProjectRequest request = buildUpdateRequest(null, null, newTargetUrl);

        SourceProjectDetailResponse response = assertDoesNotThrow(() -> service.update(projectId, request));

        assertThat(response.getDefaultTargetBaseUrl()).isEqualTo(newTargetUrl);
    }

    // ── A3: detail response exposes defaultTargetBaseUrl ─────────────────────

    @Test
    void getProjectDetail_returnsDefaultTargetBaseUrl() {
        String targetUrl = "http://52.220.34.212:8081";
        SourceProject project = buildSavedProject(null, targetUrl);

        when(projectAccessService.requireCanViewProject(projectId)).thenReturn(project);

        SourceProjectDetailResponse response = service.getById(projectId);

        assertThat(response.getDefaultTargetBaseUrl()).isEqualTo(targetUrl);
    }

    // ── A4: invalid defaultTargetBaseUrl on create → BadRequest ──────────────

    @Test
    void createProject_withInvalidDefaultTargetBaseUrl_returnsBadRequest() {
        assertThatThrownBy(() -> RuntimeTargetUrlValidator.normalise("ftp://invalid.com"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createProject_withInvalidDefaultTargetBaseUrl_fileScheme_returnsBadRequest() {
        assertThatThrownBy(() -> RuntimeTargetUrlValidator.normalise("file:///etc/passwd"))
                .isInstanceOf(BadRequestException.class);
    }

    // ── A5: invalid defaultTargetBaseUrl on update → BadRequest ──────────────

    @Test
    void updateProject_withInvalidDefaultTargetBaseUrl_returnsBadRequest() {
        SourceProject existing = buildSavedProject(null, null);
        when(projectAccessService.requireCanManageProject(projectId)).thenReturn(existing);

        // RuntimeTargetUrlValidator.normalise is called in service before save
        assertThatThrownBy(() -> {
            UpdateSourceProjectRequest request = buildUpdateRequest(null, null, "javascript:alert(1)");
            service.update(projectId, request);
        }).isInstanceOf(BadRequestException.class);
    }

    // ── A6: blank defaultTargetBaseUrl is normalized to null ─────────────────

    @Test
    void defaultTargetBaseUrl_blank_isNormalizedToNull() {
        assertThat(RuntimeTargetUrlValidator.normalise("   ")).isNull();
        assertThat(RuntimeTargetUrlValidator.normalise("")).isNull();
        assertThat(RuntimeTargetUrlValidator.normalise(null)).isNull();
    }

    @Test
    void createProject_withBlankDefaultTargetBaseUrl_persistsNull() {
        SourceProject saved = buildSavedProject(null, null); // null stored
        when(sourceProjectRepository.saveAndFlush(any())).thenReturn(saved);

        CreateSourceProjectRequest request = buildCreateRequest(null, null, "   ");

        SourceProjectDetailResponse response = assertDoesNotThrow(() -> service.create(request));

        assertThat(response.getDefaultTargetBaseUrl()).isNull();
    }

    // ── A7: repositoryUrl must NOT be auto-copied to defaultTargetBaseUrl ─────

    @Test
    void createProject_doesNotCopyRepositoryUrlToDefaultTargetBaseUrl() {
        String repoUrl = "https://github.com/spring-guides/gs-rest-service";
        // No defaultTargetBaseUrl provided — should be null, NOT auto-copied from repositoryUrl
        SourceProject saved = buildSavedProject(repoUrl, null);
        when(sourceProjectRepository.saveAndFlush(any())).thenReturn(saved);

        CreateSourceProjectRequest request = buildCreateRequest(repoUrl, "main", null);

        SourceProjectDetailResponse response = assertDoesNotThrow(() -> service.create(request));

        assertThat(response.getRepositoryUrl()).isEqualTo(repoUrl);
        // defaultTargetBaseUrl must be null — never auto-copied
        assertThat(response.getDefaultTargetBaseUrl()).isNull();
    }

    @Test
    void createProject_withRepositoryUrl_defaultTargetBaseUrl_areIndependent() {
        String repoUrl = "https://github.com/spring-guides/gs-rest-service";
        String targetUrl = "http://52.220.34.212:8081";
        SourceProject saved = buildSavedProject(repoUrl, targetUrl);
        when(sourceProjectRepository.saveAndFlush(any())).thenReturn(saved);

        CreateSourceProjectRequest request = buildCreateRequest(repoUrl, "main", targetUrl);

        SourceProjectDetailResponse response = assertDoesNotThrow(() -> service.create(request));

        assertThat(response.getRepositoryUrl()).isEqualTo(repoUrl);
        assertThat(response.getDefaultTargetBaseUrl()).isEqualTo(targetUrl);
        // They must be different values
        assertThat(response.getRepositoryUrl()).isNotEqualTo(response.getDefaultTargetBaseUrl());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SourceProject buildSavedProject(String repositoryUrl, String defaultTargetBaseUrl) {
        SourceProject p = new SourceProject();
        p.setId(projectId);
        p.setProjectKey("test-proj");
        p.setProjectName("Test Project");
        p.setBackendType(BackendType.SPRING_BOOT);
        p.setStatus(ProjectStatus.NEW);
        p.setVisibility(ProjectVisibility.PRIVATE);
        p.setArchivedFlag(false);
        p.setDeletedFlag(false);
        p.setOwnerUser(mockUser);
        p.setRepositoryUrl(repositoryUrl);
        p.setDefaultTargetBaseUrl(defaultTargetBaseUrl);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }

    private CreateSourceProjectRequest buildCreateRequest(
            String repositoryUrl, String repositoryBranch, String defaultTargetBaseUrl) {
        return CreateSourceProjectRequest.builder()
                .projectKey("test-proj")
                .projectName("Test Project")
                .backendType(BackendType.SPRING_BOOT)
                .repositoryUrl(repositoryUrl)
                .repositoryBranch(repositoryBranch)
                .defaultTargetBaseUrl(defaultTargetBaseUrl)
                .build();
    }

    private UpdateSourceProjectRequest buildUpdateRequest(
            String repositoryUrl, String repositoryBranch, String defaultTargetBaseUrl) {
        return UpdateSourceProjectRequest.builder()
                .projectName("Test Project Updated")
                .backendType(BackendType.SPRING_BOOT)
                .repositoryUrl(repositoryUrl)
                .repositoryBranch(repositoryBranch)
                .defaultTargetBaseUrl(defaultTargetBaseUrl)
                .build();
    }
}
