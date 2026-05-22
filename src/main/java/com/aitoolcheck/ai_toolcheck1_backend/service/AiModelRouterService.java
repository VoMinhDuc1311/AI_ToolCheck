package com.aitoolcheck.ai_toolcheck1_backend.service;

import java.util.UUID;

public interface AiModelRouterService {
    String executeWithFallback(String prompt);
    String routeAndExecute(String prompt, UUID jobId);
}