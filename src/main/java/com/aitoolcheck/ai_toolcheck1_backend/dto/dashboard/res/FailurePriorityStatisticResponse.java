package com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailurePriorityStatisticResponse {
    private String priority;
    private long total;
}
