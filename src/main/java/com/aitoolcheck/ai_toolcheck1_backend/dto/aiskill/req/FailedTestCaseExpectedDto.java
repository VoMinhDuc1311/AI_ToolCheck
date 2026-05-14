package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedTestCaseExpectedDto {
    private List<FailedAssertionSnapshotDto> assertions;
}
