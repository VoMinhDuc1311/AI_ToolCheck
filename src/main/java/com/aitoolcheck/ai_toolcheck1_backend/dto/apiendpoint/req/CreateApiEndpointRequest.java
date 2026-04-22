package com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.req;

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

public class CreateApiEndpointRequest {
    private UUID projectId;
    private UUID sourceFileId;
    private String controllerName;
    private String methodName;
    private HttpMethod httpMethod;
    private String endpointPath;
    private String operationId;
    private String tagName;
    private Boolean authRequired;
    private Boolean deprecatedFlag;
}
