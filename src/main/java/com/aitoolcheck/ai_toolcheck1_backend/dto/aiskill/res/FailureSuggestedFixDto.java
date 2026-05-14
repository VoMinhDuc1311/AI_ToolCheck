package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailureSuggestedFixDto {
    private String targetLayer;
    private String file;
    private String method;
    private String suggestion;
}
