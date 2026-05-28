package com.aitoolcheck.ai_toolcheck1_backend.dto.testworkbench.res;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestWorkbenchResponse {

    private UUID projectId;
    private Summary summary;
    private List<TestCaseDetailResponse> testCases;
    private List<TestRunDetailResponse> testRuns;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {
        private long totalTestCases;
        private long activeTestCases;
        private long aiGenerated;
        private long userGenerated;
        private long totalAssertions;
        private long totalRuns;
        private long completedRuns;
        private long failedRuns;
        private String latestRunStatus;
    }
}
