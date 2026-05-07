package com.aitoolcheck.ai_toolcheck1_backend.dto.testrunitem.res;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.res.TestResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestRunItemResponse {

    private UUID id;
    private UUID testRunId;
    private UUID testCaseId;
    private String caseCode;
    private String caseName;
    private Integer sortOrder;
    private ExecutionStatus itemStatus;
    /** Prepared HTTP request frame. Null until request builder runs. */
    private PreparedHttpRequestResponse preparedRequest;
    /** Execution result. Null until the item has been executed. */
    private TestResultResponse result;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
