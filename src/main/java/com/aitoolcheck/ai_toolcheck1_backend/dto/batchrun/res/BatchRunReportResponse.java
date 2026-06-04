package com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchRunReportResponse {
    private UUID batchRunId;
    private String name;
    private BatchRunStatus status;
    private Integer totalItems;
    private Integer successCount;
    private Integer failedCount;
    private Integer skippedCount;
    private Integer runningCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<BatchRunItemResponse> items;
}
