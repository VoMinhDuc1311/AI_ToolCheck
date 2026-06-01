package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HttpActualResponseDto {
    private Integer statusCode;
    private String responseBody;
    private Long responseTimeMs;
    private String errorMessage;
    /**
     * HTTP response headers as a flat case-sensitive map (key = header name, value = first value).
     * Null when no HTTP response was received (network error).
     * Used by RuleEngineService for HEADER assertion evaluation (case-insensitive lookup).
     */
    private Map<String, String> responseHeaders;
}