package com.aitoolcheck.ai_toolcheck1_backend.service;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface AiModelRouterService {
    String executeWithFallback(String prompt);
    String executeWithFallbackForSkill(
            String skillCode,
            Supplier<String> geminiPromptSupplier,
            Supplier<String> ollamaPromptSupplier,
            Consumer<String> rawResponseValidator);
    String routeAndExecute(String prompt, UUID jobId);
    String routeAndExecuteForSkill(String skillCode, String prompt, UUID jobId);
}
