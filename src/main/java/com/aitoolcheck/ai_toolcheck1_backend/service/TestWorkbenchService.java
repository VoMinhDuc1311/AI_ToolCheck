package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testworkbench.res.TestWorkbenchResponse;
import java.util.UUID;

public interface TestWorkbenchService {
    TestWorkbenchResponse getTestWorkbenchData(UUID projectId);
}
