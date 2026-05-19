package com.aitoolcheck.ai_toolcheck1_backend.dto.dashboard.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResultStatusStatisticResponse {
    private String status;
    private long total;
    private double percentage;
}
