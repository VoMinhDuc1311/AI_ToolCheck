package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceAnalysisResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceUploadVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SourceFileServiceImplTest {

    @Mock private SourceFileRepository sourceFileRepository;
    @Mock private SourceProjectRepository sourceProjectRepository;
    @Mock private SourceUploadVersionRepository sourceUploadVersionRepository;
    @Mock private ApiEndpointRepository apiEndpointRepository;
    @Mock private SourceAnalysisResultRepository sourceAnalysisResultRepository;
    @Mock private ApiDocumentRepository apiDocumentRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private ProjectNotificationEventPublisher notificationEventPublisher;

    private SourceFileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SourceFileServiceImpl(
                sourceFileRepository,
                sourceProjectRepository,
                sourceUploadVersionRepository,
                apiEndpointRepository,
                sourceAnalysisResultRepository,
                apiDocumentRepository,
                projectAccessService,
                notificationEventPublisher);
    }

    @Test
    void validZipPasses() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                zipWithEntry("src/main/java/com/example/App.java", "package com.example; public class App {}")
        );

        SourceFileUploadResponse response = service.uploadZip(projectId, file);

        assertEquals(projectId, response.getProjectId());
        assertEquals(1, response.getSavedFiles());
    }

    @Test
    void uploadZipKeepsSingleRootDirectoryInFilePath() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                zipWithEntry("repo-main/src/main/java/com/example/App.java",
                        "package com.example; public class App {}")
        );

        service.uploadZip(projectId, file);

        ArgumentCaptor<SourceFile> captor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository).save(captor.capture());
        assertEquals("repo-main/src/main/java/com/example/App.java", captor.getValue().getFilePath());
    }

    @Test
    void importZipStripsSingleRootDirectoryWhenRequested() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        service.importZip(projectId,
                new ByteArrayInputStream(zipWithEntry("repo-main/src/main/java/com/example/App.java",
                        "package com.example; public class App {}")),
                "github.zip",
                true);

        ArgumentCaptor<SourceFile> captor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository).save(captor.capture());
        assertEquals("src/main/java/com/example/App.java", captor.getValue().getFilePath());
    }

    @Test
    void importZipWithoutJavaFilesReturnsBadRequest() throws Exception {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        when(projectAccessService.requireCanUploadSource(projectId)).thenReturn(project);

        assertThrows(BadRequestException.class, () -> service.importZip(projectId,
                new ByteArrayInputStream(zipWithEntry("repo-main/README.md", "# readme")),
                "github.zip",
                true));
    }

    @Test
    void nonZipBytesRenamedZipAreRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                "not a zip".getBytes()
        );

        assertThrows(BadRequestException.class, () -> service.uploadZip(UUID.randomUUID(), file));
    }

    @Test
    void uploadZip_preservesPomXml() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                zipWithEntry("pom.xml", "<project></project>")
        );

        service.uploadZip(projectId, file);

        ArgumentCaptor<SourceFile> captor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository).save(captor.capture());
        assertEquals("pom.xml", captor.getValue().getFilePath());
    }

    @Test
    void uploadZip_preservesApplicationYaml() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                zipWithEntry("src/main/resources/application.yml", "server: port: 8080")
        );

        service.uploadZip(projectId, file);

        ArgumentCaptor<SourceFile> captor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository).save(captor.capture());
        assertEquals("src/main/resources/application.yml", captor.getValue().getFilePath());
    }

    @Test
    void zipSlipEntryIsRejected() throws Exception {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);

        when(projectAccessService.requireCanUploadSource(projectId)).thenReturn(project);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                zipWithEntry("../evil.java", "public class Evil {}")
        );

        assertThrows(BadRequestException.class, () -> service.uploadZip(projectId, file));
    }

    private byte[] zipWithEntry(String entryName, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes());
            zip.closeEntry();
        }
        return baos.toByteArray();
    }

    private void stubSuccessfulImport(UUID projectId) {
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Project");

        when(projectAccessService.requireCanUploadSource(projectId)).thenReturn(project);
        when(sourceUploadVersionRepository.findMaxVersionNoByProjectId(projectId)).thenReturn(0);
        when(sourceUploadVersionRepository.save(any(SourceUploadVersion.class))).thenAnswer(invocation -> {
            SourceUploadVersion version = invocation.getArgument(0);
            if (version.getId() == null) {
                version.setId(UUID.randomUUID());
            }
            return version;
        });
        when(sourceFileRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(sourceAnalysisResultRepository.findBySourceProjectId(projectId)).thenReturn(Optional.empty());
        when(apiDocumentRepository.findBySourceProjectId(projectId)).thenReturn(Optional.empty());
    }
}
