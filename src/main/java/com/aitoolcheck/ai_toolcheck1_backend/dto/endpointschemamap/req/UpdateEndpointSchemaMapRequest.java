package com.aitoolcheck.ai_toolcheck1_backend.dto.endpointschemamap.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UsageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class UpdateEndpointSchemaMapRequest {
    private UsageType usageType;
}
