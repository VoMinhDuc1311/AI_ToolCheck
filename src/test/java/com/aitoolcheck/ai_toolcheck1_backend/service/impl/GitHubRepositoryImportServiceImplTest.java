package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github.GitHubRepositoryCheckResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github.GitHubRepositoryImportResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ForbiddenException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.service.GitHubRepositoryCheckService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceFileService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubRepositoryImportServiceImplTest {

    @Mock private ProjectAccessService projectAccessService;
    @Mock private GitHubRepositoryCheckService gitHubRepositoryCheckService;
    @Mock private SourceFileService sourceFileService;

    @TempDir
    private Path tempDir;

    private GitHubRepositoryImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new GitHubRepositoryImportServiceImpl(
                projectAccessService,
                gitHubRepositoryCheckService,
                sourceFileService));
    }

    @Test
    void importRepositorySucceedsUsingCheckedRepositoryAndSharedZipPipeline() throws Exception {
        UUID projectId = UUID.randomUUID();
        Path zipPath = Files.createTempFile(tempDir, "repo", ".zip");
        Files.write(zipPath, new byte[] {0x50, 0x4b, 0x03, 0x04});

        SourceProject project = project(projectId, "https://github.com/owner/repo", "main");
        when(projectAccessService.requireCanUploadSource(projectId)).thenReturn(project);
        when(gitHubRepositoryCheckService.check(projectId)).thenReturn(reachableCheck("main"));
        doReturn(zipPath).when(service).downloadRepositoryZipToTempFile("owner", "repo", "main", projectId);
        when(sourceFileService.importZip(eq(projectId), any(InputStream.class), eq("repo-main.zip"), eq(true)))
                .thenReturn(SourceFileUploadResponse.builder()
                        .savedFiles(2)
                        .ignoredFiles(1)
                        .build());

        GitHubRepositoryImportResponse response = service.importRepository(projectId);

        assertEquals(projectId, response.getProjectId());
        assertEquals("SUCCESS", response.getImportStatus());
        assertEquals(2, response.getImportedFileCount());
        assertEquals(1, response.getSkippedFileCount());
        assertEquals(3, response.getTotalFileCount());
        verify(sourceFileService).importZip(eq(projectId), any(InputStream.class), eq("repo-main.zip"), eq(true));
    }

    @Test
    void noRepositoryUrlConfiguredReturnsBadRequest() {
        UUID projectId = UUID.randomUUID();
        when(projectAccessService.requireCanUploadSource(projectId)).thenReturn(project(projectId, null, null));

        assertThrows(BadRequestException.class, () -> service.importRepository(projectId));
        verifyNoInteractions(gitHubRepositoryCheckService, sourceFileService);
    }

    @Test
    void unreachableRepositoryBlocksImport() {
        UUID projectId = UUID.randomUUID();
        when(projectAccessService.requireCanUploadSource(projectId))
                .thenReturn(project(projectId, "https://github.com/owner/repo", "main"));
        when(gitHubRepositoryCheckService.check(projectId)).thenReturn(GitHubRepositoryCheckResponse.builder()
                .reachable(false)
                .message("Repository not found or is private (HTTP 404).")
                .build());

        assertThrows(BadRequestException.class, () -> service.importRepository(projectId));
        verifyNoInteractions(sourceFileService);
    }

    @Test
    void invalidRedirectHostIsRejected() {
        URI source = URI.create("https://api.github.com/repos/owner/repo/zipball/main");

        assertThrows(BadRequestException.class,
                () -> service.validateRedirectLocation(source, "https://evil.example/repo.zip"));
    }

    @Test
    void noUploadPermissionBlocksImport() {
        UUID projectId = UUID.randomUUID();
        when(projectAccessService.requireCanUploadSource(projectId))
                .thenThrow(new ForbiddenException("You do not have permission to perform this action"));

        assertThrows(ForbiddenException.class, () -> service.importRepository(projectId));
        verifyNoInteractions(gitHubRepositoryCheckService, sourceFileService);
    }

    private SourceProject project(UUID projectId, String repositoryUrl, String repositoryBranch) {
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setRepositoryUrl(repositoryUrl);
        project.setRepositoryBranch(repositoryBranch);
        return project;
    }

    private GitHubRepositoryCheckResponse reachableCheck(String branch) {
        return GitHubRepositoryCheckResponse.builder()
                .reachable(true)
                .provider("GITHUB")
                .owner("owner")
                .repositoryName("repo")
                .repositoryUrl("https://github.com/owner/repo")
                .branch(branch)
                .defaultBranch(branch)
                .message("ok")
                .build();
    }
}
