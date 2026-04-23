package com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;

import java.util.UUID;

public class TestResultResponse {
    private UUID id;
    private UUID testRunItemId;
    private Integer actualStatus;
    private ResultStatus resultStatus;
    private Integer responseTime;
    private String errorMessage;


}
