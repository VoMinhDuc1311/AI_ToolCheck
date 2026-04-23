package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCaseResponse {

    private UUID id;
    private String caseCode;
    private String caseName;
    private CaseType caseType;
    private PriorityLevel priorityLevel;
    private GeneratedBy generatedBy;
    private Boolean activeFlag;
}
