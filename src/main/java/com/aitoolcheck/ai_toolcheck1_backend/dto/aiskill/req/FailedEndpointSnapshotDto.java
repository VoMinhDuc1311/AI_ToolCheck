package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedEndpointSnapshotDto {
    private UUID endpointId;
    private HttpMethod httpMethod;
    private String endpointPath;
    private String controllerName;
    private String methodName;
    private Boolean authRequired;
}
