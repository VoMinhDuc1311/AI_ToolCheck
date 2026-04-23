package com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class UpdateTestCaseInputRequest {
    private String inputData;
}
