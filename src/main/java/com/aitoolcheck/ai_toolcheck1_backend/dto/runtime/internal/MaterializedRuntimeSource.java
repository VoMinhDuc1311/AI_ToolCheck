package com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal;

import lombok.Builder;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Getter
@Builder
public class MaterializedRuntimeSource implements AutoCloseable {
    private final UUID projectId;
    private final UUID sourceVersionId;
    private final Path rootDir;
    private final List<String> materializedFiles;

    public void cleanup() {
        if (rootDir == null || !Files.exists(rootDir)) {
            return;
        }

        Path normalizedRoot = rootDir.toAbsolutePath().normalize();
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!normalizedRoot.startsWith(tempRoot)) {
            throw new IllegalStateException("Refusing to cleanup runtime source outside temp directory: " + normalizedRoot);
        }

        try (Stream<Path> walk = Files.walk(normalizedRoot)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        cleanup();
    }
}
