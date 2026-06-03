package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.MaterializedRuntimeSource;

import java.util.UUID;

public interface RuntimeSourceMaterializer {

    MaterializedRuntimeSource materialize(UUID projectId);
}
