package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
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
import jakarta.persistence.Column;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    // -----------------------------------------------------------------------
    // Phase 0 — file-type detection tests (regression guard for BUILD / APP_CONFIG / SCRIPT)
    // -----------------------------------------------------------------------

    @Test
    void uploadZip_persistsBuildFileType() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip",
                zipWithEntry("pom.xml", "<project></project>")
        );

        service.uploadZip(projectId, file);

        ArgumentCaptor<SourceFile> captor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository).save(captor.capture());
        assertEquals(FileType.BUILD, captor.getValue().getFileType(),
                "pom.xml must be persisted with FileType.BUILD");
    }

    @Test
    void uploadZip_persistsAppConfigFileType() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip",
                zipWithEntry("src/main/resources/application.yml", "server:\n  port: 8080")
        );

        service.uploadZip(projectId, file);

        ArgumentCaptor<SourceFile> captor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository).save(captor.capture());
        assertEquals(FileType.APP_CONFIG, captor.getValue().getFileType(),
                "application.yml must be persisted with FileType.APP_CONFIG");
    }

    @Test
    void uploadZip_persistsScriptFileType() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip",
                zipWithEntry("mvnw", "#!/bin/sh")
        );

        service.uploadZip(projectId, file);

        ArgumentCaptor<SourceFile> captor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository).save(captor.capture());
        assertEquals(FileType.SCRIPT, captor.getValue().getFileType(),
                "mvnw must be persisted with FileType.SCRIPT");
    }

    @Test
    void existingJavaFileTypeStillPersists() throws Exception {
        UUID projectId = UUID.randomUUID();
        stubSuccessfulImport(projectId);

        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip",
                zipWithEntry("src/main/java/com/example/api/UserController.java",
                        "package com.example.api; public class UserController {}")
        );

        service.uploadZip(projectId, file);

        ArgumentCaptor<SourceFile> captor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository).save(captor.capture());
        assertEquals(FileType.CONTROLLER, captor.getValue().getFileType(),
                "UserController.java must still be detected as CONTROLLER");
    }

    @Test
    void detectFileType_pomXml_returnsBuild() throws Exception {
        Method m = SourceFileServiceImpl.class.getDeclaredMethod("detectFileType", Path.class, String.class);
        m.setAccessible(true);
        FileType result = (FileType) m.invoke(service, Path.of("pom.xml"), "pom.xml");
        assertEquals(FileType.BUILD, result, "detectFileType(pom.xml) must return BUILD");
    }

    @Test
    void detectFileType_applicationYml_returnsAppConfig() throws Exception {
        Method m = SourceFileServiceImpl.class.getDeclaredMethod("detectFileType", Path.class, String.class);
        m.setAccessible(true);
        FileType result = (FileType) m.invoke(service, Path.of("src/main/resources/application.yml"), "application.yml");
        assertEquals(FileType.APP_CONFIG, result, "detectFileType(application.yml) must return APP_CONFIG");
    }

    @Test
    void detectFileType_mvnw_returnsScript() throws Exception {
        Method m = SourceFileServiceImpl.class.getDeclaredMethod("detectFileType", Path.class, String.class);
        m.setAccessible(true);
        FileType result = (FileType) m.invoke(service, Path.of("mvnw"), "mvnw");
        assertEquals(FileType.SCRIPT, result, "detectFileType(mvnw) must return SCRIPT");
    }

    @Test
    void sourceFile_fileTypeColumnSupportsAppConfigLength() throws NoSuchFieldException {
        // Guard: @Column(length) on SourceFile.fileType must accommodate APP_CONFIG (10 chars)
        // and all current FileType values.  If someone shrinks the column length below the
        // longest enum value this test will catch it before it reaches the DB.
        Field field = SourceFile.class.getDeclaredField("fileType");
        Column col = field.getAnnotation(Column.class);
        int columnLength = col.length(); // defaults to 255 if not set; after fix should be 50

        for (FileType ft : FileType.values()) {
            assertTrue(ft.name().length() <= columnLength,
                    "FileType." + ft.name() + " (" + ft.name().length() + " chars) exceeds "
                            + "@Column(length=" + columnLength + ") on SourceFile.fileType");
        }
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
