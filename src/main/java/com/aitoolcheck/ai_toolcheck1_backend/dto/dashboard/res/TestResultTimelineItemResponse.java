package com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResultTimelineItemResponse {
    private LocalDate bucketStartDate;
    private LocalDate bucketEndDate;
    private String dateLabel;
    private Integer dayOfWeek;
    private String dayLabel;
    private Long total;
    private Long passCount;
    private Long failCount;
    private Long errorCount;
    private Long skippedCount;
}
