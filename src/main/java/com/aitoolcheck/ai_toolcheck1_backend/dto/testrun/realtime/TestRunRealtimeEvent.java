package com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.realtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestRunRealtimeEvent {

    private UUID projectId;
    private UUID testRunId;
    private UUID testRunItemId;
    private UUID testCaseId;
    private String eventType;
    private String runStatus;
    private String itemStatus;
    private String resultStatus;
    private String caseCode;
    private String caseName;
    private Integer sortOrder;
    private Integer actualStatus;
    private Integer responseTimeMs;
    private String errorMessage;
    private String blockedReason;
    private String actualResponseJson;
    private Integer totalItems;
    private Integer completedItems;
    private Integer successItems;
    private Integer failedItems;
    private Integer errorItems;
    private LocalDateTime occurredAt;
}
