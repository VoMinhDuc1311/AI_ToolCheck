package com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestResultDetailResponse {

    private UUID id;
    private UUID testRunItemId;
    private Integer actualStatus;
    private ResultStatus resultStatus;
    private Integer responseTimeMs;
    private JsonNode actualResponseJson;
    private String errorMessage;
    private String blockedReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
