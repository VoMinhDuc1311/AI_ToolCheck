package com.aitoolcheck.ai_toolcheck1_backend.dto.apiparameter.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
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

public class UpdateApiParameterRequest {
    private String paramName;
    private ParamIn paramIn;
    private String dataType;
    private Boolean requiredFlag;
    private String exampleValue;
}
