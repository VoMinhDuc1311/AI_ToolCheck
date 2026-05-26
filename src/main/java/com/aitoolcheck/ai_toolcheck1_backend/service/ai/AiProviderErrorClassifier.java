package com.aitoolcheck.ai_toolcheck1_backend.service.ai;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

@Service
public class AiProviderErrorClassifier {

    public String classify(Throwable throwable) {
        if (throwable == null) {
            return "AI_PROVIDER_FAILED";
        }
        if (hasCause(throwable, TimeoutException.class)) {
            return "AI_TIMEOUT";
        }
        return classify(messageChain(throwable));
    }

    public String classify(String message) {
        if (message == null || message.isBlank()) {
            return "AI_PROVIDER_FAILED";
        }

        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "AI_TIMEOUT";
        }
        if (lower.contains("quota") || lower.contains("resource_exhausted")) {
            return "AI_QUOTA_EXCEEDED";
        }
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return "AI_RATE_LIMITED";
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("unauthorized")
                || lower.contains("forbidden") || lower.contains("api key")) {
            return "AI_UNAUTHORIZED";
        }
        if (lower.contains("connection refused") || lower.contains("connection reset")
                || lower.contains("connect timed out") || lower.contains("unknown host")) {
            return "AI_CONNECTION_FAILED";
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
            return "AI_PROVIDER_FAILED";
        }
        return "AI_PROVIDER_FAILED";
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
