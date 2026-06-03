package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OllamaApiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelRouterServiceImpl implements AiModelRouterService {

    private static final String OLLAMA = "Ollama";
    private static final String GEMINI = "Gemini";
    private static final String SKILL_ENRICH_API_DOC = "enrich_api_doc";
    private static final String SKILL_GENERATE_TEST_CASE = "GENERATE_TEST_CASE";

    private final OllamaApiClientService ollamaApiClientService;
    private final GeminiApiClientService geminiApiClientService;
    private final OllamaProperties ollamaProperties;
    private final AiJobLogService aiJobLogService;
    private final AiOptimizationProperties aiOptimizationProperties;
    private final AiPayloadOptimizerService aiPayloadOptimizerService;
    private final GeminiProperties geminiProperties;

    @Override
    public String executeWithFallback(String prompt) {
        String optimizedPrompt = optimizePrompt(prompt);
        List<AiProviderFailureException> failures = new ArrayList<>();
        Set<String> attempted = new HashSet<>();

        boolean tryOllama = true;
        if (aiOptimizationProperties.isEnabled() && aiOptimizationProperties.getRouter().isFailFastLocalProvider()) {
            if (!ollamaApiClientService.isHealthy()) {
                log.warn("[Router] Ollama health check failed. Skipping local provider.");
                failures.add(AiProviderFailureException.unavailable(
                        OLLAMA, ollamaProperties.getPrimaryModel(), "health check failed", null));
                tryOllama = false;
            }
        }

        if (tryOllama) {
            String tier1 = tryOllamaCandidate(
                    "Tier1", ollamaProperties.getPrimaryModel(), optimizedPrompt, attempted, failures);
            if (tier1 != null) {
                return tier1;
            }

            String tier2 = tryOllamaCandidate(
                    "Tier2", ollamaProperties.getFallbackModel(), optimizedPrompt, attempted, failures);
            if (tier2 != null) {
                return tier2;
            }
        }

        String geminiResult = tryGeminiCandidate(optimizedPrompt, attempted, failures);
        if (geminiResult != null) {
            return geminiResult;
        }

        throwAllProvidersFailed(failures);
        return null;
    }

    @Override
    public String executeWithFallbackForSkill(
            String skillCode,
            Supplier<String> geminiPromptSupplier,
            Supplier<String> ollamaPromptSupplier,
            Consumer<String> rawResponseValidator) {
        if (!isGeminiFirstSkill(skillCode)) {
            return executeWithFallback(geminiPromptSupplier.get());
        }

        List<AiProviderFailureException> failures = new ArrayList<>();
        Set<String> attempted = new HashSet<>();

        String geminiResult = tryGeminiCandidate(
                "Tier1", geminiPromptSupplier.get(), attempted, failures, rawResponseValidator);
        if (geminiResult != null) {
            return geminiResult;
        }

        String ollamaResult = tryOllamaCandidate(
                "Tier2",
                ollamaProperties.getPrimaryModel(),
                ollamaPromptSupplier.get(),
                attempted,
                failures,
                rawResponseValidator,
                getOllamaTimeoutSecondsForSkill(skillCode));
        if (ollamaResult != null) {
            return ollamaResult;
        }

        throwAllProvidersFailed(failures);
        return null;
    }

    @Override
    public String routeAndExecute(String prompt, UUID jobId) {
        String optimizedPrompt = optimizePrompt(prompt);
        int tokenInput = optimizedPrompt.length() / 4;
        int tokenOutput = 0;

        String result = executeWithFallback(optimizedPrompt);
        if (result == null || result.isBlank()) {
            throw AiProviderFailureException.emptyResponse("LLM", "router", null);
        }
        tokenOutput = result.length() / 4;

        try {
            aiJobLogService.markJobAsSuccess(jobId, tokenInput, tokenOutput, "router-selected");
            log.info("[Router] Updated JobLog {} as SUCCESS", jobId);
        } catch (Exception e) {
            log.warn("[Router] Could not update JobLog {}: {}", jobId, e.getMessage());
        }

        return result;
    }

    @Override
    public String routeAndExecuteForSkill(String skillCode, String prompt, UUID jobId) {
        String optimizedPrompt = optimizePrompt(prompt);
        int tokenInput = optimizedPrompt.length() / 4;

        String result = executeWithFallbackForSkill(skillCode, () -> optimizedPrompt, () -> optimizedPrompt, null);
        if (result == null || result.isBlank()) {
            throw AiProviderFailureException.emptyResponse("LLM", "router", null);
        }

        int tokenOutput = result.length() / 4;
        try {
            aiJobLogService.markJobAsSuccess(jobId, tokenInput, tokenOutput, "router-selected");
            log.info("[Router] Updated JobLog {} as SUCCESS", jobId);
        } catch (Exception e) {
            log.warn("[Router] Could not update JobLog {}: {}", jobId, e.getMessage());
        }

        return result;
    }

    private boolean isGeminiFirstSkill(String skillCode) {
        return SKILL_ENRICH_API_DOC.equalsIgnoreCase(skillCode)
                || SKILL_GENERATE_TEST_CASE.equalsIgnoreCase(skillCode);
    }

    private int getOllamaTimeoutSecondsForSkill(String skillCode) {
        if (SKILL_ENRICH_API_DOC.equalsIgnoreCase(skillCode)) {
            return ollamaProperties.getEnrichApiDocTimeoutSeconds();
        }
        if (SKILL_GENERATE_TEST_CASE.equalsIgnoreCase(skillCode)) {
            return ollamaProperties.getGenerateTestCaseTimeoutSeconds();
        }
        return ollamaProperties.getReadTimeoutSeconds();
    }

    private String tryOllamaCandidate(
            String tier,
            String model,
            String prompt,
            Set<String> attempted,
            List<AiProviderFailureException> failures) {
        return tryOllamaCandidate(
                tier, model, prompt, attempted, failures, null, ollamaProperties.getReadTimeoutSeconds());
    }

    private String tryOllamaCandidate(
            String tier,
            String model,
            String prompt,
            Set<String> attempted,
            List<AiProviderFailureException> failures,
            Consumer<String> rawResponseValidator,
            int timeoutSeconds) {
        String key = OLLAMA + "/" + model;
        if (!attempted.add(key)) {
            log.warn("Skipping duplicate provider candidate: {}", key);
            return null;
        }

        try {
            log.info("[Router][{}] Calling Ollama model={} promptChars={} requestedTimeoutSeconds={}",
                    tier, model, prompt.length(), timeoutSeconds);
            String result = ollamaApiClientService.generateTextWithModel(prompt, model, timeoutSeconds);
            if (result == null || result.isBlank()) {
                throw AiProviderFailureException.emptyResponse(OLLAMA, model, null);
            }
            validateRawResponse(rawResponseValidator, result, OLLAMA, model);
            log.info("[Router][{}] Ollama model={} succeeded.", tier, model);
            return result;
        } catch (Exception ex) {
            // Pass timeoutSeconds so that if the client didn't already produce a typed
            // LLM_TIMEOUT failure, toProviderFailure uses the skill-specific value.
            AiProviderFailureException failure = toProviderFailure(ex, OLLAMA, model, prompt.length(), timeoutSeconds);
            failures.add(failure);
            log.warn("[Router][{}] {} failed: {}", tier, key, failure.getMessage());
            return null;
        }
    }

    private String tryGeminiCandidate(
            String prompt,
            Set<String> attempted,
            List<AiProviderFailureException> failures) {
        return tryGeminiCandidate("Tier3", prompt, attempted, failures, null);
    }

    private String tryGeminiCandidate(
            String tier,
            String prompt,
            Set<String> attempted,
            List<AiProviderFailureException> failures,
            Consumer<String> rawResponseValidator) {
        String model = geminiProperties.getModel();
        String key = GEMINI + "/" + model;
        if (!attempted.add(key)) {
            log.warn("Skipping duplicate provider candidate: {}", key);
            return null;
        }

        try {
            int throttleMs = Integer.getInteger("ai.router.geminiThrottleMs", 15_000);
            log.info("[Router][{}] Throttling {}ms before Gemini Cloud call.", tier, throttleMs);
            Thread.sleep(throttleMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[Router][{}] Gemini throttle interrupted. Continuing.", tier);
        }

        try {
            log.info("[Router][{}] Calling Gemini Cloud model={} promptChars={}", tier, model, prompt.length());
            String result = geminiApiClientService.generateText(prompt);
            if (result == null || result.isBlank()) {
                throw AiProviderFailureException.emptyResponse(GEMINI, model, null);
            }
            validateRawResponse(rawResponseValidator, result, GEMINI, model);
            log.info("[Router][{}] Gemini Cloud succeeded.", tier);
            return result;
        } catch (Exception ex) {
            AiProviderFailureException failure = toProviderFailure(ex, GEMINI, model, prompt.length());
            failures.add(failure);
            log.error("[Router][{}] Gemini Cloud failed: {}", tier, failure.getMessage());
            return null;
        }
    }

    private void validateRawResponse(
            Consumer<String> rawResponseValidator,
            String rawResponse,
            String provider,
            String model) {
        if (rawResponseValidator == null) {
            return;
        }
        try {
            rawResponseValidator.accept(rawResponse);
        } catch (Exception ex) {
            throw AiProviderFailureException.unavailable(
                    provider, model, "raw response validation failed: " + rootMessage(ex), ex);
        }
    }

    private String optimizePrompt(String prompt) {
        String safePrompt = prompt == null ? "" : prompt;
        return aiOptimizationProperties.isEnabled()
                ? aiPayloadOptimizerService.truncateIfNeeded(safePrompt, aiOptimizationProperties.getMaxPromptChars())
                : safePrompt;
    }

    private void throwAllProvidersFailed(List<AiProviderFailureException> failures) {
        String summary = summarizeFailures(failures);
        log.error("[Router] All LLM providers failed: {}", summary);
        throw AiProviderFailureException.allProvidersFailed(summary,
                failures.isEmpty() ? null : failures.get(failures.size() - 1));
    }

    private String summarizeFailures(List<AiProviderFailureException> failures) {
        if (failures.isEmpty()) {
            return "No LLM provider candidate was available.";
        }
        return failures.stream()
                .map(this::summarizeFailure)
                .distinct()
                .reduce((left, right) -> left + "; " + right)
                .orElse("All LLM providers failed.");
    }

    private String summarizeFailure(AiProviderFailureException failure) {
        String provider = failure.getProvider() == null ? "LLM" : failure.getProvider();
        String model = failure.getModel() == null ? "unknown" : failure.getModel();
        if (AiProviderFailureException.LLM_TIMEOUT.equals(failure.getErrorCode())) {
            Integer timeout = failure.getTimeoutSeconds();
            return provider + " " + model + " timed out"
                    + (timeout == null ? "" : " after " + timeout + "s");
        }
        if (AiProviderFailureException.LLM_RATE_LIMITED.equals(failure.getErrorCode())) {
            Integer retryAfter = failure.getRetryAfterSeconds();
            return provider + " " + model + " quota/rate limit exceeded"
                    + (retryAfter == null ? "" : ", retry after " + retryAfter + "s");
        }
        return provider + " " + model + " failed: " + stripCode(failure.getMessage());
    }

    private AiProviderFailureException toProviderFailure(
            Throwable throwable,
            String provider,
            String model,
            int promptChars) {
        return toProviderFailure(throwable, provider, model, promptChars,
                OLLAMA.equals(provider) ? ollamaProperties.getReadTimeoutSeconds() : 60);
    }

    private AiProviderFailureException toProviderFailure(
            Throwable throwable,
            String provider,
            String model,
            int promptChars,
            int effectiveTimeoutSeconds) {
        AiProviderFailureException typed = findProviderFailure(throwable);
        if (typed != null) {
            return typed;
        }
        if (containsTimeout(throwable)) {
            return AiProviderFailureException.timeout(provider, model, effectiveTimeoutSeconds, promptChars, throwable);
        }
        return AiProviderFailureException.unavailable(provider, model, rootMessage(throwable), throwable);
    }

    private AiProviderFailureException findProviderFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            Throwable unwrapped = reactor.core.Exceptions.unwrap(current);
            if (unwrapped instanceof AiProviderFailureException providerFailure) {
                return providerFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean containsTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("timeout") || lower.contains("timed out")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        Throwable last = throwable;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        return last.getMessage() == null ? throwable.getMessage() : last.getMessage();
    }

    private String stripCode(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceFirst("^\\[[A-Z_]+] ", "");
    }
}
