package com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class TestCaseInputResponse {
    private UUID id;
    private UUID testCaseId;
    private HttpMethod httpMethod;
    private String requestPath;
    private JsonNode queryParamsJson;
    private JsonNode headersJson;
    private JsonNode requestBodyJson;
    private String contentType;
    private Integer timeoutMs;
    private String inputData;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
