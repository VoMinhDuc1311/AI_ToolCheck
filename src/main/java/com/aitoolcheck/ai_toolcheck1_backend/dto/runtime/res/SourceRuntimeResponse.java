package com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceRuntimeResponse {

    private UUID id;
    private UUID projectId;
    private UUID sourceVersionId;
    private RuntimeMode runtimeMode;
    private RuntimeStatus runtimeStatus;
    private RuntimeType runtimeType;
    private String publicBaseUrl;
    private Integer detectedPort;
    private String contextPath;
    private String healthCheckPath;
    private String lastHealthStatus;
    private String lastError;
    private LocalDateTime buildStartedAt;
    private LocalDateTime buildFinishedAt;
    private LocalDateTime startedAt;
    private LocalDateTime stoppedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
