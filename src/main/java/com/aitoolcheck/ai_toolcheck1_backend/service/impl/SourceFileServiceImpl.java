package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceFileService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class SourceFileServiceImpl implements SourceFileService {

    private final SourceFileRepository sourceFileRepository;
    private final SourceProjectRepository sourceProjectRepository;

    @Override
    @Transactional
    public SourceFileUploadResponse uploadZip(UUID projectId, MultipartFile file) {
        validateZipFile(file);

        SourceProject sourceProject = sourceProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Source project not found with id: " + projectId));

        try {
            final Path workingDir = createWorkingDirectory(projectId);

            try {
                final String originalFilename = file.getOriginalFilename();
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

                List<SourceFile> sourceFiles = javaFiles.stream()
                        .map(path -> mapToSourceFile(path, extractDir, sourceProject))
                        .toList();

                sourceFileRepository.deleteBySourceProjectId(projectId);
                List<SourceFile> savedFiles = sourceFileRepository.saveAll(sourceFiles);

                sourceProject.setStatus(ProjectStatus.UPLOADED);
                sourceProjectRepository.save(sourceProject);

                return SourceFileUploadResponse.builder()
                        .projectId(sourceProject.getId())
                        .projectName(sourceProject.getProjectName())
                        .totalJavaFilesFound(javaFiles.size())
                        .savedFiles(savedFiles.size())
                        .ignoredFiles(ignoredFiles)
                        .build();

            } finally {
                deleteDirectoryQuietly(workingDir);
            }

        } catch (IOException e) {
            throw new BadRequestException("Failed to process uploaded zip file: " + e.getMessage());
        }
    }

    @Override
    public List<SourceFileResponse> getByProjectId(UUID projectId) {
        if (!sourceProjectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Source project not found with id: " + projectId);
        }

        return sourceFileRepository.findBySourceProjectId(projectId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public SourceFileDetailResponse getById(UUID id) {
        SourceFile sourceFile = sourceFileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Source file not found with id: " + id));

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
                || normalized.startsWith("build/");
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

    private SourceFile mapToSourceFile(Path javaFile, Path extractDir, SourceProject sourceProject) {
        Path relativePath = extractDir.relativize(javaFile);
        String fileName = javaFile.getFileName().toString();

        return SourceFile.builder()
                .filePath(relativePath.toString().replace("\\", "/"))
                .fileName(fileName)
                .packageName(extractPackageName(javaFile))
                .className(extractClassName(fileName))
                .fileType(detectFileType(relativePath, fileName))
                .checksumSha256(calculateSha256(javaFile))
                .sourceContent(readSourceContent(javaFile))
                .parsedFlag(Boolean.FALSE)
                .sourceProject(sourceProject)
                .build();
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
        String normalizedPath = relativePath.toString().replace("\\", "/").toLowerCase();
        String normalizedFileName = fileName.toLowerCase();

        if (normalizedPath.contains("/controller/") || normalizedFileName.contains("controller")) {
            return FileType.CONTROLLER;
        }
        if (normalizedPath.contains("/service/") || normalizedFileName.contains("service")) {
            return FileType.SERVICE;
        }
        if (normalizedPath.contains("/repository/") || normalizedFileName.contains("repository")) {
            return FileType.REPOSITORY;
        }
        if (normalizedPath.contains("/entity/") || normalizedFileName.contains("entity")) {
            return FileType.ENTITY;
        }
        if (normalizedPath.contains("/dto/") || normalizedFileName.contains("dto")) {
            return FileType.DTO;
        }
        if (normalizedPath.contains("/config/") || normalizedFileName.contains("config")) {
            return FileType.CONFIG;
        }
        if (normalizedPath.contains("/util/") || normalizedFileName.contains("util")) {
            return FileType.UTIL;
        }
        if (normalizedPath.contains("/model/") || normalizedFileName.contains("model")) {
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
                .parsedFlag(sourceFile.getParsedFlag())
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
}