package com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal;

import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RuntimeDetectionResult {
    RuntimeType runtimeType;
    boolean supported;
    String message;
    Integer detectedPort;
    String contextPath;
    String buildFilePath;
    String configFilePath;
    /**
     * Relative path of the project root within the materialized source tree.
     * {@code null} means the build files are at the materialized root itself.
     * Example: {@code "aitc-standard-springboot-api"} for a single-folder ZIP layout.
     */
    String projectRoot;
}
