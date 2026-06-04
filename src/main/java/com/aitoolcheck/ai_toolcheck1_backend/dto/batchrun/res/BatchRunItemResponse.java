package com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunItemStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStep;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchRunItemResponse {
    private UUID id;
    private UUID batchRunId;
    private UUID projectId;
    private BatchRunStep currentStep;
    private BatchRunItemStatus status;
    private Integer retryCount;
    private Integer maxRetries;
    private String errorMessage;
    private UUID apiDocumentVersionId;
    private UUID testRunId;
    private UUID runtimeId;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
