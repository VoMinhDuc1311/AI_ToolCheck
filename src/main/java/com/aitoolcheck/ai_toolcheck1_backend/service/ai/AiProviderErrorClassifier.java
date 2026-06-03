package com.aitoolcheck.ai_toolcheck1_backend.service.ai;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;

@Service
public class AiProviderErrorClassifier {

    public String classify(Throwable throwable) {
        if (throwable == null) {
            return AiProviderFailureException.LLM_PROVIDER_UNAVAILABLE;
        }
        if (throwable instanceof AiProviderFailureException providerFailure) {
            return providerFailure.getErrorCode();
        }
        if (hasCause(throwable, TimeoutException.class)) {
            return AiProviderFailureException.LLM_TIMEOUT;
        }
        return classify(messageChain(throwable));
    }

    public String classify(String message) {
        if (message == null || message.isBlank()) {
            return AiProviderFailureException.LLM_PROVIDER_UNAVAILABLE;
        }

        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("llm_all_providers_failed")) {
            return AiProviderFailureException.LLM_ALL_PROVIDERS_FAILED;
        }
        if (lower.contains("llm_empty_response")) {
            return AiProviderFailureException.LLM_EMPTY_RESPONSE;
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return AiProviderFailureException.LLM_TIMEOUT;
        }
        if (lower.contains("quota") || lower.contains("resource_exhausted")
                || lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return AiProviderFailureException.LLM_RATE_LIMITED;
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("unauthorized")
                || lower.contains("forbidden") || lower.contains("api key")) {
            return "AI_UNAUTHORIZED";
        }
        if (lower.contains("connection refused") || lower.contains("connection reset")
                || lower.contains("connect timed out") || lower.contains("unknown host")) {
            return AiProviderFailureException.LLM_PROVIDER_UNAVAILABLE;
        }
        if (lower.contains("invalid json") || lower.contains("malformed json")
                || lower.contains("no json opening delimiter") || lower.contains("no closing delimiter")) {
            return "AI_RESPONSE_INVALID";
        }
        if (lower.contains("no endpoint") || lower.contains("endpoints must not be empty")) {
            return "AI_NO_ENDPOINTS_FOR_ENTRYPOINT";
        }
        if (lower.contains("5xx") || lower.contains("500") || lower.contains("502")
                || lower.contains("503") || lower.contains("504") || lower.contains("server error")) {
            return AiProviderFailureException.LLM_PROVIDER_UNAVAILABLE;
        }
        return AiProviderFailureException.LLM_PROVIDER_UNAVAILABLE;
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String messageChain(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                if (!sb.isEmpty()) {
                    sb.append(" | ");
                }
                sb.append(current.getMessage());
            }
            current = current.getCause();
        }
        return sb.toString();
    }
}
