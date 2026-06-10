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

    /**
     * Same routing as {@link #routeAndExecuteForSkill} but does NOT mark the job
     * SUCCESS — caller is responsible for marking SUCCESS after parse+persist.
     * Use this when there is downstream processing (parse, DB save) that must
     * succeed before the job can be considered done.
     */
    String routeAndExecuteForSkillRaw(String skillCode, String prompt);
}
