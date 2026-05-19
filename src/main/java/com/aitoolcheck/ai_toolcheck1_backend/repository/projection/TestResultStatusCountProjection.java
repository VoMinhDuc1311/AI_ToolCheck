package com.aitoolcheck.ai_toolcheck1_backend.repository.projection;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;

public interface TestResultStatusCountProjection {
    ResultStatus getStatus();
    Long getTotal();
}
