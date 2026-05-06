package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.res.TestCaseAssertionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.res.TestCaseInputResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCaseDetailResponse {

    private UUID id;
    private UUID projectId;
    private UUID apiEndpointId;
    private UUID apiDocumentVersionId;
    private String caseCode;
    private String caseName;
    private String description;
    private CaseType caseType;
    private PriorityLevel priorityLevel;
    private GeneratedBy generatedBy;
    private Boolean activeFlag;
    private Boolean deletedFlag;
    private Boolean requiresWrite;
    private Boolean cleanupRequired;
    private TestCaseInputResponse input;
    private List<TestCaseAssertionResponse> assertions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}