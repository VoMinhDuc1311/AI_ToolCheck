package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceUploadStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceAnalysisResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceUploadVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceFileService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class SourceFileServiceImpl implements SourceFileService {

    private final SourceFileRepository sourceFileRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final SourceUploadVersionRepository sourceUploadVersionRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final SourceAnalysisResultRepository sourceAnalysisResultRepository;
    private final ApiDocumentRepository apiDocumentRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectNotificationEventPublisher notificationEventPublisher;

    @Override
    @Transactional
    public SourceFileUploadResponse uploadZip(UUID projectId, MultipartFile file) {
        SourceProject sourceProject = projectAccessService.requireCanUploadSource(projectId);

        validateZipFile(file);

        try {
            final Path workingDir = createWorkingDirectory(projectId);

            try {
                final String originalFilename = sanitizeOriginalFilename(file.getOriginalFilename());
                final Path zipPath = workingDir.resolve(originalFilename);
                final Path extractDir = workingDir.resolve("extracted");

                Files.createDirectories(extractDir);
                Files.copy(file.getInputStream(), zipPath, StandardCopyOption.REPLACE_EXISTING);

                unzipSafely(zipPath, extractDir);

                List<Path> javaFiles;
                try (Stream<Path> stream = Files.walk(extractDir)) {
                    javaFiles = stream
                            .filter(Files::isRegularFile)
                            .filter(this::isJavaFile)
                            .filter(path -> !shouldIgnorePath(extractDir.relativize(path)))
                            .toList();
                }

                if (javaFiles.isEmpty()) {
                    throw new BadRequestException("No Java files found in uploaded zip");
                }

                int ignoredFiles = countIgnoredFiles(extractDir);

                int nextVersionNo = sourceUploadVersionRepository.findMaxVersionNoByProjectId(projectId) + 1;
                SourceUploadVersion uploadVersion = sourceUploadVersionRepository.save(SourceUploadVersion.builder()
                        .sourceProject(sourceProject)
                        .versionNo(nextVersionNo)
                        .originalFileName(originalFilename)
                        .status(SourceUploadStatus.PROCESSING)
                        .build());

                Map<String, UploadedJavaFile> uploadedByPath = new HashMap<>();
                for (Path javaFile : javaFiles) {
                    UploadedJavaFile uploaded = toUploadedJavaFile(javaFile, extractDir);
                    if (uploadedByPath.put(uploaded.filePath(), uploaded) != null) {
                        throw new BadRequestException("Duplicate Java file path in uploaded zip: " + uploaded.filePath());
                    }
                }

                List<SourceFile> existingFiles = sourceFileRepository.findBySourceProjectId(projectId);
                Map<String, SourceFile> existingByPath = new HashMap<>();
                for (SourceFile existingFile : existingFiles) {
                    existingByPath.put(normalizeRelativePath(existingFile.getFilePath()), existingFile);
                }

                int addedFiles = 0;
                int updatedFiles = 0;
                int unchangedFiles = 0;
                int deletedFiles = 0;

                for (UploadedJavaFile uploaded : uploadedByPath.values()) {
                    SourceFile existing = existingByPath.get(uploaded.filePath());
                    if (existing == null) {
                        SourceFile newFile = buildNewSourceFile(uploaded, sourceProject, uploadVersion);
                        sourceFileRepository.save(newFile);
                        addedFiles++;
                        continue;
                    }

                    existing.setLastSeenUploadVersion(uploadVersion);
                    existing.setActiveFlag(true);
                    existing.setDeletedFlag(false);

                    if (uploaded.checksumSha256().equals(existing.getChecksumSha256())) {
                        sourceFileRepository.save(existing);
                        unchangedFiles++;
                    } else {
                        applyUploadedContent(existing, uploaded, uploadVersion);
                        sourceFileRepository.save(existing);
                        markEndpointsStaleForSourceFile(existing);
                        updatedFiles++;
                    }
                }

                Set<String> uploadedPaths = uploadedByPath.keySet();
                for (SourceFile existing : existingFiles) {
                    String existingPath = normalizeRelativePath(existing.getFilePath());
                    if (Boolean.TRUE.equals(existing.getActiveFlag()) && !uploadedPaths.contains(existingPath)) {
                        existing.setActiveFlag(false);
                        existing.setDeletedFlag(true);
                        existing.setLastSeenUploadVersion(uploadVersion);
                        sourceFileRepository.save(existing);
                        markEndpointsStaleForSourceFile(existing);
                        deletedFiles++;
                    }
                }

                sourceProject.setStatus(ProjectStatus.UPLOADED);
                sourceProjectRepository.save(sourceProject);
                markAnalysisStale(projectId);
                markDocumentStale(projectId);

                int savedFiles = addedFiles + updatedFiles + unchangedFiles;
                String summary = "Upload version " + nextVersionNo + " synchronized. Added: " + addedFiles
                        + ", updated: " + updatedFiles
                        + ", unchanged: " + unchangedFiles
                        + ", deleted: " + deletedFiles + ".";

                uploadVersion.setTotalJavaFilesFound(javaFiles.size());
                uploadVersion.setSavedFiles(savedFiles);
                uploadVersion.setIgnoredFiles(ignoredFiles);
                uploadVersion.setAddedFiles(addedFiles);
                uploadVersion.setUpdatedFiles(updatedFiles);
                uploadVersion.setUnchangedFiles(unchangedFiles);
                uploadVersion.setDeletedFiles(deletedFiles);
                uploadVersion.setStatus(SourceUploadStatus.COMPLETED);
                uploadVersion.setCompletedAt(java.time.LocalDateTime.now());
                sourceUploadVersionRepository.save(uploadVersion);

                notificationEventPublisher.publishForCurrentUser(
                        projectId,
                        NotificationType.SOURCE_UPLOAD_COMPLETED,
                        NotificationSeverity.SUCCESS,
                        "Source upload completed",
                        "Source ZIP upload completed for " + sourceProject.getProjectName() + ".",
                        "/source-projects/" + projectId,
                        Map.of(
                                "projectId", projectId,
                                "uploadVersionId", uploadVersion.getId(),
                                "filename", originalFilename
                        ));

                return SourceFileUploadResponse.builder()
                        .projectId(sourceProject.getId())
                        .projectName(sourceProject.getProjectName())
                        .uploadVersionId(uploadVersion.getId())
                        .versionNo(uploadVersion.getVersionNo())
                        .totalJavaFilesFound(javaFiles.size())
                        .savedFiles(savedFiles)
                        .ignoredFiles(ignoredFiles)
                        .addedFiles(addedFiles)
                        .updatedFiles(updatedFiles)
                        .unchangedFiles(unchangedFiles)
                        .deletedFiles(deletedFiles)
                        .status(ProjectStatus.UPLOADED.name())
                        .summary("Source snapshot synchronized successfully. " + summary)
                        .build();

            } finally {
                deleteDirectoryQuietly(workingDir);
            }

        } catch (IOException e) {
            throw new BadRequestException("Failed to process uploaded zip file: " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceFileResponse> getByProjectId(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);

        return sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SourceFileDetailResponse getById(UUID id) {
        SourceFile sourceFile = sourceFileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Source file not found with id: " + id));
        projectAccessService.requireCanViewSourceFileContent(sourceFile.getSourceProject().getId());

        return mapToDetailResponse(sourceFile);
    }

    private void validateZipFile(MultipartFile file) {
        if (file == null) {
            throw new BadRequestException("Uploaded file must not be null");
        }

        if (file.isEmpty()) {
            throw new BadRequestException("Uploaded file must not be empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            throw new BadRequestException("Only .zip files are supported");
        }

        // Magic-byte check: ZIP files begin with PK (0x50 0x4B)
        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[4];
            int read = is.read(header, 0, 4);
            if (read < 2 || header[0] != 0x50 || header[1] != 0x4B) {
                throw new BadRequestException("Uploaded file is not a valid ZIP archive");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new BadRequestException("Unable to validate uploaded file format");
        }
    }

    private String sanitizeOriginalFilename(String originalFilename) {
        String sanitized = Path.of(originalFilename == null ? "source.zip" : originalFilename).getFileName().toString();
        if (sanitized.isBlank()) {
            return "source.zip";
        }
        return sanitized;
    }

    private Path createWorkingDirectory(UUID projectId) throws IOException {
        String tempDir = System.getProperty("java.io.tmpdir");
        Path baseDir = Path.of(tempDir, "ai-toolcheck", "source-upload", projectId.toString(), UUID.randomUUID().toString());
        return Files.createDirectories(baseDir);
    }

    private void unzipSafely(Path zipPath, Path extractDir) throws IOException {
        try (InputStream inputStream = Files.newInputStream(zipPath);
             ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(inputStream))) {

            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path targetPath = extractDir.resolve(entry.getName()).normalize();

                if (!targetPath.startsWith(extractDir.normalize())) {
                    throw new BadRequestException("Zip contains invalid path entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(zipInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }

                zipInputStream.closeEntry();
            }
        }
    }

    private boolean shouldIgnorePath(Path relativePath) {
        String normalized = relativePath.toString().replace("\\", "/").toLowerCase();

        return normalized.contains("/.idea/")
                || normalized.startsWith(".idea/")
                || normalized.contains("/target/")
                || normalized.startsWith("target/")
                || normalized.contains("/node_modules/")
                || normalized.startsWith("node_modules/")
                || normalized.contains("/.git/")
                || normalized.startsWith(".git/")
                || normalized.contains("/dist/")
                || normalized.startsWith("dist/")
                || normalized.contains("/build/")
                || normalized.startsWith("build/")
                || normalized.contains("/.gradle/")
                || normalized.startsWith(".gradle/")
                || normalized.contains("/out/")
                || normalized.startsWith("out/");
    }

    private boolean isJavaFile(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".java");
    }

    private int countIgnoredFiles(Path extractDir) throws IOException {
        try (Stream<Path> stream = Files.walk(extractDir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isJavaFile(path) || shouldIgnorePath(extractDir.relativize(path)))
                    .count();
        }
    }

    private String readSourceContent(Path javaFile) {
        try {
            return Files.readString(javaFile);
        } catch (IOException e) {
            throw new BadRequestException("Failed to read source content for file: " + javaFile.getFileName());
        }
    }

    private UploadedJavaFile toUploadedJavaFile(Path javaFile, Path extractDir) {
        Path relativePath = extractDir.relativize(javaFile);
        String fileName = javaFile.getFileName().toString();

        return new UploadedJavaFile(
                normalizeRelativePath(relativePath.toString()),
                fileName,
                extractPackageName(javaFile),
                extractClassName(fileName),
                detectFileType(relativePath, fileName),
                calculateSha256(javaFile),
                readSourceContent(javaFile)
        );
    }

    private SourceFile buildNewSourceFile(UploadedJavaFile uploaded, SourceProject sourceProject, SourceUploadVersion uploadVersion) {
        return SourceFile.builder()
                .filePath(uploaded.filePath())
                .fileName(uploaded.fileName())
                .packageName(uploaded.packageName())
                .className(uploaded.className())
                .fileType(uploaded.fileType())
                .checksumSha256(uploaded.checksumSha256())
                .sourceContent(uploaded.sourceContent())
                .parsedFlag(Boolean.FALSE)
                .parseError(null)
                .activeFlag(Boolean.TRUE)
                .deletedFlag(Boolean.FALSE)
                .sourceProject(sourceProject)
                .uploadVersion(uploadVersion)
                .lastSeenUploadVersion(uploadVersion)
                .build();
    }

    private void applyUploadedContent(SourceFile sourceFile, UploadedJavaFile uploaded, SourceUploadVersion uploadVersion) {
        sourceFile.setFilePath(uploaded.filePath());
        sourceFile.setFileName(uploaded.fileName());
        sourceFile.setPackageName(uploaded.packageName());
        sourceFile.setClassName(uploaded.className());
        sourceFile.setFileType(uploaded.fileType());
        sourceFile.setChecksumSha256(uploaded.checksumSha256());
        sourceFile.setSourceContent(uploaded.sourceContent());
        sourceFile.setParsedFlag(Boolean.FALSE);
        sourceFile.setParseError(null);
        sourceFile.setActiveFlag(Boolean.TRUE);
        sourceFile.setDeletedFlag(Boolean.FALSE);
        sourceFile.setUploadVersion(uploadVersion);
        sourceFile.setLastSeenUploadVersion(uploadVersion);
    }

    private void markEndpointsStaleForSourceFile(SourceFile sourceFile) {
        if (sourceFile.getId() == null) {
            return;
        }
        List<ApiEndpoint> endpoints = apiEndpointRepository.findByProjectIdAndSourceFileIdIn(
                sourceFile.getSourceProject().getId(),
                List.of(sourceFile.getId())
        );
        for (ApiEndpoint endpoint : endpoints) {
            endpoint.setActiveFlag(Boolean.FALSE);
            endpoint.setStaleFlag(Boolean.TRUE);
        }
        apiEndpointRepository.saveAll(endpoints);
    }

    private void markAnalysisStale(UUID projectId) {
        sourceAnalysisResultRepository.findBySourceProjectId(projectId)
                .ifPresent(result -> {
                    result.setCurrentFlag(Boolean.FALSE);
                    sourceAnalysisResultRepository.save(result);
                });
    }

    private void markDocumentStale(UUID projectId) {
        apiDocumentRepository.findBySourceProjectId(projectId)
                .ifPresent(document -> {
                    document.setStaleFlag(Boolean.TRUE);
                    apiDocumentRepository.save(document);
                });
    }

    private String normalizeRelativePath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace("\\", "/").replaceAll("/+", "/");
    }

    private String extractPackageName(Path javaFile) {
        try (Stream<String> lines = Files.lines(javaFile)) {
            return lines
                    .map(String::trim)
                    .filter(line -> line.startsWith("package ") && line.endsWith(";"))
                    .map(line -> line.substring(8, line.length() - 1).trim())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String extractClassName(String fileName) {
        if (fileName == null || !fileName.endsWith(".java")) {
            return null;
        }
        return fileName.substring(0, fileName.length() - 5);
    }

    private FileType detectFileType(Path relativePath, String fileName) {
        String path = relativePath.toString().replace("\\", "/").toLowerCase();
        String name = fileName == null ? "" : fileName.toLowerCase();

        if (path.startsWith("src/test/") || path.contains("/src/test/") || name.endsWith("test.java") || name.endsWith("tests.java")) {
            return FileType.TEST;
        }
        if (name.endsWith("application.java")) {
            return FileType.APPLICATION;
        }
        if (path.contains("/controller/") || name.contains("controller")) {
            return FileType.CONTROLLER;
        }
        if (path.contains("/service/impl/") || name.endsWith("serviceimpl.java")) {
            return FileType.SERVICE_IMPL;
        }
        if (path.contains("/service/") || name.contains("service")) {
            return FileType.SERVICE;
        }
        if (path.contains("/repository/") || path.contains("/respository/") || name.contains("repository")) {
            return FileType.REPOSITORY;
        }
        if (path.contains("/entity/") || name.contains("entity")) {
            return FileType.ENTITY;
        }
        if (path.contains("/request/") || path.contains("/req/") || name.endsWith("request.java")) {
            return FileType.REQUEST;
        }
        if (path.contains("/response/") || path.contains("/res/") || name.endsWith("response.java")) {
            return FileType.RESPONSE;
        }
        if (path.contains("/dto/") || name.contains("dto")) {
            return FileType.DTO;
        }
        if (path.contains("/security/") || path.contains("/auth/") || name.contains("security") || name.contains("jwt") || name.contains("token")) {
            return FileType.SECURITY;
        }
        if (name.contains("filter")) {
            return FileType.FILTER;
        }
        if (name.contains("interceptor")) {
            return FileType.INTERCEPTOR;
        }
        if (name.contains("exceptionhandler") || name.contains("advice")) {
            return FileType.EXCEPTION_HANDLER;
        }
        if (name.contains("exception")) {
            return FileType.EXCEPTION;
        }
        if (path.contains("/config/") || name.contains("config")) {
            return FileType.CONFIG;
        }
        if (path.contains("/enum/") || path.contains("/enums/")) {
            return FileType.ENUM;
        }
        if (path.contains("/mapper/") || name.contains("mapper")) {
            return FileType.MAPPER;
        }
        if (name.contains("validator")) {
            return FileType.VALIDATOR;
        }
        if (name.contains("constant") || name.contains("constants") || name.contains("errorcode")) {
            return FileType.CONSTANT;
        }
        if (path.contains("/util/") || path.contains("/utils/") || name.contains("util") || name.contains("helper")) {
            return FileType.UTIL;
        }
        if (path.contains("/model/")) {
            return FileType.MODEL;
        }

        return FileType.UNKNOWN;
    }

    private String calculateSha256(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(filePath);
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BadRequestException("Failed to calculate checksum for file: " + filePath.getFileName());
        }
    }

    private SourceFileResponse mapToResponse(SourceFile sourceFile) {
        return SourceFileResponse.builder()
                .id(sourceFile.getId())
                .projectId(sourceFile.getSourceProject().getId())
                .fileName(sourceFile.getFileName())
                .filePath(sourceFile.getFilePath())
                .fileType(sourceFile.getFileType())
                .checksumSha256(sourceFile.getChecksumSha256())
                .parsedFlag(sourceFile.getParsedFlag())
                .parseError(sourceFile.getParseError())
                .activeFlag(sourceFile.getActiveFlag())
                .deletedFlag(sourceFile.getDeletedFlag())
                .uploadVersionId(sourceFile.getUploadVersion() == null ? null : sourceFile.getUploadVersion().getId())
                .lastSeenUploadVersionId(sourceFile.getLastSeenUploadVersion() == null ? null : sourceFile.getLastSeenUploadVersion().getId())
                .createdAt(sourceFile.getCreatedAt())
                .updatedAt(sourceFile.getUpdatedAt())
                .build();
    }

    private SourceFileDetailResponse mapToDetailResponse(SourceFile sourceFile) {
        return SourceFileDetailResponse.builder()
                .id(sourceFile.getId())
                .projectId(sourceFile.getSourceProject().getId())
                .filePath(sourceFile.getFilePath())
                .fileName(sourceFile.getFileName())
                .packageName(sourceFile.getPackageName())
                .className(sourceFile.getClassName())
                .fileType(sourceFile.getFileType())
                .checksumSha256(sourceFile.getChecksumSha256())
                .parsedFlag(sourceFile.getParsedFlag())
                .parseError(sourceFile.getParseError())
                .activeFlag(sourceFile.getActiveFlag())
                .deletedFlag(sourceFile.getDeletedFlag())
                .uploadVersionId(sourceFile.getUploadVersion() == null ? null : sourceFile.getUploadVersion().getId())
                .lastSeenUploadVersionId(sourceFile.getLastSeenUploadVersion() == null ? null : sourceFile.getLastSeenUploadVersion().getId())
                .createdAt(sourceFile.getCreatedAt())
                .updatedAt(sourceFile.getUpdatedAt())
                .build();
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private record UploadedJavaFile(
            String filePath,
            String fileName,
            String packageName,
            String className,
            FileType fileType,
            String checksumSha256,
            String sourceContent
    ) {
    }
}
