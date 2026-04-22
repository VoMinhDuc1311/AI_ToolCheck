package com.aitoolcheck.ai_toolcheck1_backend.dto.apiparameter.res;

import java.util.UUID;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiParameterDetailResponse {

    private UUID id;
    private UUID apiEndpointId;
    private String paramName;
    private ParamIn paramIn;
    private String dataType;
    private Boolean requiredFlag;
    private String exampleValue;
}
