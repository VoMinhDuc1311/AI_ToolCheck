package com.aitoolcheck.ai_toolcheck1_backend.repository.projection;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import java.time.LocalDateTime;

public interface TestResultTimelineProjection {
    LocalDateTime getCreatedAt();
    ResultStatus getResultStatus();
}
