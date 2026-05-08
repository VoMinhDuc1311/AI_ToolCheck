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
}
