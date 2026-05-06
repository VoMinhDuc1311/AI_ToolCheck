package com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.CreateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.CreateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class CreateTestCaseRequest {

    @NotNull(message = "projectId is required")
    private UUID projectId;

    private UUID apiEndpointId;

    private UUID apiDocumentVersionId;

    @Size(max = 100, message = "caseCode must not exceed 100 characters")
    private String caseCode;

    @NotBlank(message = "caseName is required")
    @Size(max = 150, message = "caseName must not exceed 150 characters")
    private String caseName;

    @Size(max = 2000, message = "description must not exceed 2000 characters")
    private String description;

    private CaseType caseType;

    private PriorityLevel priorityLevel;

    private GeneratedBy generatedBy;

    private Boolean activeFlag;

    private Boolean requiresWrite;

    private Boolean cleanupRequired;

    @Valid
    @NotNull(message = "input is required")
    private CreateTestCaseInputRequest input;

    @Valid
    @NotEmpty(message = "assertions must not be empty")
    private List<CreateTestCaseAssertionRequest> assertions;
}
