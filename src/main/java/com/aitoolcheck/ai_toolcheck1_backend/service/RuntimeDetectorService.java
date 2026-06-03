package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.internal.RuntimeDetectionResult;

import java.util.UUID;

public interface RuntimeDetectorService {

    RuntimeDetectionResult detect(UUID projectId);
}
