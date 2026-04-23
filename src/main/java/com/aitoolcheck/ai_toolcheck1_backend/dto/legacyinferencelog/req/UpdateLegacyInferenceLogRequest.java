package com.aitoolcheck.ai_toolcheck1_backend.dto.legacyinferencelog.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateLegacyInferenceLogRequest {

    private String inferredMetadataJson;
    private BigDecimal confidenceScore;
}