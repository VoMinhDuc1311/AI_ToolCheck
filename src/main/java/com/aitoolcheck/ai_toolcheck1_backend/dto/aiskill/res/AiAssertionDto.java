package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Defensive DTO — ánh xạ một assertion từ JSON output của AI.
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} bảo vệ khỏi trường hợp AI
 * sinh dư field.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiAssertionDto {

    @JsonProperty("assertion_type")
    private String assertionType;

    @JsonProperty("target_path")
    private String targetPath;

    @JsonProperty("operator")
    private String operator;

    @JsonProperty("expected_value")
    private String expectedValue;
}