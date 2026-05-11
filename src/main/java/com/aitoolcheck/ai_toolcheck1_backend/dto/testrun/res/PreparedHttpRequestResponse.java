package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fasterxml.jackson.databind.JsonNode;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreparedHttpRequestResponse {
    private HttpMethod method;
    private String finalUrl;
    private String baseUrl;
    private String requestPath;
    private JsonNode headers;
    private JsonNode queryParams;
    private JsonNode body;
    private String contentType;
    private Integer timeoutMs;
}
