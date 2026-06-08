package com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchRunResponse {
    private UUID id;
    private String name;
    private BatchRunStatus status;
    private Integer totalItems;
    private Integer successCount;
    private Integer failedCount;
    private Integer skippedCount;
    private Integer runningCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private UUID createdBy;
    private String errorMessage;
    private BuildStrategy buildStrategy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
