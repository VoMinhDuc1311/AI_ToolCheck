package com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.DashboardGroupBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DashboardRange;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResultTimelineResponse {
    private DashboardRange range;
    private DashboardGroupBy groupBy;
    private String timezone;
    private LocalDate fromDate;
    private LocalDate toDate;
    private List<TestResultTimelineItemResponse> items;
}
