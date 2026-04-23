package com.aitoolcheck.ai_toolcheck1_backend.dto.testrunitem.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class UpdateTestRunItemRequest {
    private UUID testRunId;
    private UUID testCaseId;
}
