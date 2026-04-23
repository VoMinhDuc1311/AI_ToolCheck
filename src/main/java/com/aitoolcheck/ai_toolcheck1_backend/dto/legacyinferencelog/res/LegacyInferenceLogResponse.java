package com.aitoolcheck.ai_toolcheck1_backend.dto.legacyinferencelog.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegacyInferenceLogResponse {

    private UUID id;
    private UUID projectId;
    private UUID sourceFileId;
    private UUID apiEndpointId;
    private BigDecimal confidenceScore;
}