package com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApiEndpointResponse {

    private UUID id;
    private UUID projectId;
    private HttpMethod httpMethod;
    private String endpointPath;
    private String stableKey;
    private String operationId;
    private String tagName;
    private Boolean authRequired;
    private Boolean deprecatedFlag;
    private Boolean activeFlag;
    private Boolean staleFlag;
    private Boolean aiEnrichedFlag;
    private String aiSummary;
    private java.time.LocalDateTime aiEnrichedAt;
}
