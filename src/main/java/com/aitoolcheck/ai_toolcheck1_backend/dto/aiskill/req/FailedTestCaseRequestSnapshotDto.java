package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedTestCaseRequestSnapshotDto {
    private String baseUrl;
    private String requestPath;
    private String fullUrl;
    private HttpMethod httpMethod;
    private String queryParamsJson;
    private String headersJson;
    private String maskedHeadersJson;
    private String requestBodyJson;
    private String maskedRequestBodyJson;
    private String contentType;
}
