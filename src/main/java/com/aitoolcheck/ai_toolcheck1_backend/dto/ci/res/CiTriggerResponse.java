package com.aitoolcheck.ai_toolcheck1_backend.dto.ci.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
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
public class CiTriggerResponse {
    private UUID testRunId;
    private String runCode;
    private RunStatus runStatus;
    private Integer totalItems;
    private Boolean executionStarted;
    private String branchName;
    private String commitSha;
    private LocalDateTime createdAt;
}
