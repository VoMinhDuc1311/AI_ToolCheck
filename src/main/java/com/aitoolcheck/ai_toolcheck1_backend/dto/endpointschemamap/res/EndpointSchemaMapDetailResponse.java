package com.aitoolcheck.ai_toolcheck1_backend.dto.endpointschemamap.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UsageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class EndpointSchemaMapDetailResponse {
    private UUID id;
    private UUID apiEndpointId;
    private UUID apiSchemaId;
    private UsageType usageType;
}
