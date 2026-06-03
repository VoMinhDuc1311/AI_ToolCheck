package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeDetectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeDetectorServiceImpl implements RuntimeDetectorService {

    private static final List<String> CONFIG_PRIORITY = List.of(
            "src/main/resources/application.yml",
            "src/main/resources/application.yaml",
            "src/main/resources/application.properties",
            "application.yml",
            "application.yaml",
            "application.properties"
    );

    private final SourceFileRepository sourceFileRepository;

    @Override
    public RuntimeDetectionResult detect(UUID projectId) {
        List<SourceFile> sourceFiles = projectId == null
                ? List.of()
                : sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId);
        return detectFiles(sourceFiles);
    }

    RuntimeDetectionResult detectFiles(List<SourceFile> sourceFiles) {
        List<SourceFile> files = sourceFiles == null ? List.of() : sourceFiles;
        Optional<SourceFile> configFile = findConfigFile(files);
        ConfigMetadata config = configFile
                .map(file -> parseConfig(normalizedPath(file.getFilePath()), file))
                .orElse(ConfigMetadata.empty());

        Optional<SourceFile> pom = findByNormalizedPath(files, "pom.xml");
        if (pom.isPresent() && hasSpringBootMavenEvidence(safeContent(pom.get()))) {
            return supported(RuntimeType.SPRING_BOOT_MAVEN, "Spring Boot Maven project detected.",
                    pom.get(), configFile.orElse(null), config);
        }

        Optional<SourceFile> gradle = findGradleBuildFile(files);
        if (gradle.isPresent() && hasSpringBootGradleEvidence(safeContent(gradle.get()))) {
            return supported(RuntimeType.SPRING_BOOT_GRADLE, "Spring Boot Gradle project detected.",
                    gradle.get(), configFile.orElse(null), config);
        }

        if (pom.isPresent()) {
            return unsupported("Maven project found, but no Spring Boot evidence was detected.",
                    pom.get(), configFile.orElse(null), config);
        }

        if (gradle.isPresent()) {
            return unsupported("Gradle project found, but no Spring Boot evidence was detected.",
                    gradle.get(), configFile.orElse(null), config);
        }

        return unsupported("No supported Spring Boot build file found.",
                null, configFile.orElse(null), config);
    }

    private RuntimeDetectionResult supported(RuntimeType runtimeType, String message, SourceFile buildFile,
                                             SourceFile configFile, ConfigMetadata config) {
        return RuntimeDetectionResult.builder()
                .runtimeType(runtimeType)
                .supported(true)
                .message(message)
                .detectedPort(config.detectedPort())
                .contextPath(config.contextPath())
                .buildFilePath(buildFile == null ? null : buildFile.getFilePath())
                .configFilePath(configFile == null ? null : configFile.getFilePath())
                .build();
    }

    private RuntimeDetectionResult unsupported(String message, SourceFile buildFile, SourceFile configFile,
                                               ConfigMetadata config) {
        return RuntimeDetectionResult.builder()
                .runtimeType(RuntimeType.UNSUPPORTED)
                .supported(false)
                .message(message)
                .detectedPort(config.detectedPort())
                .contextPath(config.contextPath())
                .buildFilePath(buildFile == null ? null : buildFile.getFilePath())
                .configFilePath(configFile == null ? null : configFile.getFilePath())
                .build();
    }

    private Optional<SourceFile> findByNormalizedPath(List<SourceFile> files, String expectedPath) {
        String expected = normalizedPath(expectedPath);
        return files.stream()
                .filter(file -> normalizedPath(file.getFilePath()).equals(expected))
                .findFirst();
    }

    private Optional<SourceFile> findGradleBuildFile(List<SourceFile> files) {
        return files.stream()
                .filter(file -> {
                    String path = normalizedPath(file.getFilePath());
                    return path.equals("build.gradle") || path.equals("build.gradle.kts");
                })
                .sorted(Comparator.comparing(file -> normalizedPath(file.getFilePath())))
                .findFirst();
    }

    private Optional<SourceFile> findConfigFile(List<SourceFile> files) {
        for (String configPath : CONFIG_PRIORITY) {
            Optional<SourceFile> match = findByNormalizedPath(files, configPath);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private boolean hasSpringBootMavenEvidence(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("spring-boot-starter")
                || lower.contains("spring-boot-maven-plugin")
                || lower.contains("spring-boot-dependencies")
                || lower.contains("org.springframework.boot");
    }

    private boolean hasSpringBootGradleEvidence(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("org.springframework.boot")
                || lower.contains("spring-boot-starter")
                || lower.contains("io.spring.dependency-management");
    }

    private ConfigMetadata parseConfig(String normalizedPath, SourceFile file) {
        String content = safeContent(file);
        if (normalizedPath.endsWith(".properties")) {
            return parsePropertiesConfig(content);
        }
        return parseYamlConfig(content);
    }

    private ConfigMetadata parsePropertiesConfig(String content) {
        String contextPath = null;
        Integer port = null;

        for (String rawLine : content.split("\\R", -1)) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int separator = firstSeparator(line);
            if (separator < 0) {
                continue;
            }

            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ("server.servlet.context-path".equals(key)) {
                contextPath = normalizeContextPath(value);
            } else if ("server.port".equals(key)) {
                port = parsePort(value);
            }
        }

        return new ConfigMetadata(port, contextPath);
    }

    private ConfigMetadata parseYamlConfig(String content) {
        String contextPath = null;
        Integer port = null;
        boolean inServer = false;
        int serverIndent = -1;
        boolean inServlet = false;
        int servletIndent = -1;

        for (String rawLine : content.split("\\R", -1)) {
            String withoutComment = stripComment(rawLine);
            if (withoutComment.trim().isEmpty()) {
                continue;
            }

            int indent = leadingSpaces(withoutComment);
            String line = withoutComment.trim();

            if (inServlet && indent <= servletIndent && !line.startsWith("context-path:")) {
                inServlet = false;
            }
            if (inServer && indent <= serverIndent && !line.equals("server:")) {
                inServer = false;
                inServlet = false;
            }

            if (line.equals("server:")) {
                inServer = true;
                serverIndent = indent;
                inServlet = false;
                continue;
            }

            if (!inServer) {
                continue;
            }

            if (line.equals("servlet:")) {
                inServlet = true;
                servletIndent = indent;
                continue;
            }

            if (line.startsWith("port:")) {
                port = parsePort(line.substring("port:".length()).trim());
            } else if (inServlet && line.startsWith("context-path:")) {
                contextPath = normalizeContextPath(line.substring("context-path:".length()).trim());
            }
        }

        return new ConfigMetadata(port, contextPath);
    }

    private int firstSeparator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0) {
            return colon;
        }
        if (colon < 0) {
            return equals;
        }
        return Math.min(equals, colon);
    }

    private String stripComment(String line) {
        if (line == null) {
            return "";
        }
        int comment = line.indexOf('#');
        return comment >= 0 ? line.substring(0, comment) : line;
    }

    private int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }

    private Integer parsePort(String rawValue) {
        String value = unquote(rawValue);
        if (value == null || !value.matches("\\d+")) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 && parsed <= 65535 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeContextPath(String rawValue) {
        String value = unquote(rawValue);
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim().replace("\\", "/").replaceAll("/+", "/");
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return "/".equals(value) ? null : value;
    }

    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private String normalizedPath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace("\\", "/").replaceAll("/+", "/").trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String safeContent(SourceFile file) {
        return file == null || file.getSourceContent() == null ? "" : file.getSourceContent();
    }

    private record ConfigMetadata(Integer detectedPort, String contextPath) {
        static ConfigMetadata empty() {
            return new ConfigMetadata(null, null);
        }
    }
}
