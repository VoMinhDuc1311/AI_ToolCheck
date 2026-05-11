package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res;

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
public class HttpActualResponseDto {
    private Integer statusCode;
    private String responseBody;
    private Long responseTimeMs;
    private String errorMessage;
}