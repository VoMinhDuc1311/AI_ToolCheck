package com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApiEndpointDetailResponse {
    private UUID id;
    private UUID projectId;
    private UUID sourceFileId;
    private UUID sourceUploadVersionId;
    private String controllerName;
    private String methodName;
    private HttpMethod httpMethod;
    private String endpointPath;
    private String stableKey;
    private String description;
    private String operationId;
    private String tagName;
    private Boolean authRequired;
    private Boolean deprecatedFlag;
    private Boolean activeFlag;
    private Boolean staleFlag;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Boolean aiEnrichedFlag;
    private String aiSummary;
    private String aiDescription;
    private String exampleRequestJson;
    private String exampleResponseJson;
    private String openapiFragmentJson;
    private LocalDateTime aiEnrichedAt;
    private UUID lastAiJobLogId;

    // Added relations/stats fields
    private SourceFileInfo sourceFile;
    private LatestAiJobInfo latestAiJob;
    private EndpointStats stats;

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SourceFileInfo {
        private UUID id;
        private String fileName;
        private String filePath;
        private String packageName;
        private String className;
    }

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class LatestAiJobInfo {
        private UUID id;
        private String jobType;
        private String executionStatus;
        private String errorMessage;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
    }

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EndpointStats {
        private Integer testCaseCount;
        private Integer assertionCount;
        private String latestRunStatus;
        private Integer failureCount;
    }
}
