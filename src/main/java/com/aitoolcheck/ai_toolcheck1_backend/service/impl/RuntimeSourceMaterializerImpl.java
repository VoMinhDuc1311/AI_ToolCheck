package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeSourceMaterializerImpl implements RuntimeSourceMaterializer {

    private final SourceFileRepository sourceFileRepository;

    @Override
    public MaterializedRuntimeSource materialize(UUID projectId) {
        List<SourceFile> files = projectId == null
                ? List.of()
                : sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId);
        return materialize(projectId, files);
    }

    MaterializedRuntimeSource materialize(UUID projectId, List<SourceFile> sourceFiles) {
        Path root = null;
        try {
            root = createRoot(projectId);
            List<String> materializedFiles = new ArrayList<>();
            Set<String> seenPaths = new HashSet<>();
            UUID sourceVersionId = null;

            for (SourceFile sourceFile : sourceFiles == null ? List.<SourceFile>of() : sourceFiles) {
                if (!shouldMaterialize(sourceFile)) {
                    continue;
                }

                String relative = normalizeAndValidateRelativePath(sourceFile.getFilePath());
                String duplicateKey = relative.toLowerCase(Locale.ROOT);
                if (!seenPaths.add(duplicateKey)) {
                    throw new IllegalArgumentException("Duplicate source file path for runtime materialization: " + relative);
                }

                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("Source file path escapes runtime materialization root: " + relative);
                }

                Files.createDirectories(target.getParent());
                Files.writeString(target, sourceFile.getSourceContent() == null ? "" : sourceFile.getSourceContent(),
                        StandardCharsets.UTF_8);
                maybeSetExecutable(target, relative);
                materializedFiles.add(relative);

                if (sourceVersionId == null && sourceFile.getLastSeenUploadVersion() != null) {
                    sourceVersionId = sourceFile.getLastSeenUploadVersion().getId();
                }
                if (sourceVersionId == null && sourceFile.getUploadVersion() != null) {
                    sourceVersionId = sourceFile.getUploadVersion().getId();
                }
            }

            return MaterializedRuntimeSource.builder()
                    .projectId(projectId)
                    .sourceVersionId(sourceVersionId)
                    .rootDir(root)
                    .materializedFiles(List.copyOf(materializedFiles))
                    .build();
        } catch (RuntimeException | IOException ex) {
            cleanupQuietly(root);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalArgumentException("Failed to materialize runtime source: " + ex.getMessage(), ex);
        }
    }

    Path createRoot(UUID projectId) throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"), "ai-toolcheck", "runtime-source");
        Files.createDirectories(base);
        String prefix = "project-" + (projectId == null ? "unknown" : projectId) + "-";
        return Files.createTempDirectory(base, prefix).toAbsolutePath().normalize();
    }

    private boolean shouldMaterialize(SourceFile sourceFile) {
        if (sourceFile == null) {
            return false;
        }
        FileType fileType = sourceFile.getFileType();
        String fileName = sourceFile.getFileName() == null ? "" : sourceFile.getFileName().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".java")) {
            return true;
        }
        if (fileType == FileType.BUILD
                || fileType == FileType.APP_CONFIG
                || fileType == FileType.SCRIPT) {
            return true;
        }
        return fileType != null && fileType != FileType.UNKNOWN;
    }

    private String normalizeAndValidateRelativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Source file path is required for runtime materialization.");
        }
        if (rawPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Source file path contains null byte.");
        }
        String trimmed = rawPath.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            throw new IllegalArgumentException("Absolute source file path is not allowed: " + rawPath);
        }
        if (trimmed.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new IllegalArgumentException("Windows drive source file path is not allowed: " + rawPath);
        }

        String normalized = trimmed.replace("\\", "/").replaceAll("/+", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Source file path is required for runtime materialization.");
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Path traversal is not allowed in source file path: " + rawPath);
            }
        }
        return normalized;
    }

    private void maybeSetExecutable(Path target, String relativePath) {
        if (isWindows()) {
            return;
        }
        String name = Path.of(relativePath).getFileName().toString();
        if (!name.equals("mvnw") && !name.equals("gradlew")) {
            return;
        }
        try {
            if (!target.toFile().setExecutable(true, false)) {
                log.warn("Failed to set executable permission for runtime wrapper script: {}", relativePath);
            }
        } catch (SecurityException ex) {
            log.warn("Cannot set executable permission for runtime wrapper script: {}", relativePath);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void cleanupQuietly(Path root) {
        if (root == null) {
            return;
        }
        try {
            MaterializedRuntimeSource.builder().rootDir(root).build().cleanup();
        } catch (RuntimeException ignored) {
        }
    }
}
