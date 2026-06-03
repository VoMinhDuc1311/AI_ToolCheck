package com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res;

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
public class RuntimeActionResponse {

    private String code;
    private String message;
    private SourceRuntimeResponse runtime;
}
