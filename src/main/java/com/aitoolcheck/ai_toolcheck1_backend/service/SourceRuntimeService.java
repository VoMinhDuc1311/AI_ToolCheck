package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.RuntimeActionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.SourceRuntimeResponse;

import java.util.UUID;

public interface SourceRuntimeService {

    SourceRuntimeResponse getCurrentRuntime(UUID projectId);

    SourceRuntimeResponse ensureRuntimeReady(UUID projectId);

    RuntimeActionResponse startRuntime(UUID projectId);

    RuntimeActionResponse rebuildRuntime(UUID projectId);

    RuntimeActionResponse stopRuntime(UUID projectId);
}
