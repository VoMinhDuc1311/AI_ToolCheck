package com.aitoolcheck.ai_toolcheck1_backend.exception;

public class AiProviderFailureException extends RuntimeException {

    public static final String LLM_TIMEOUT = "LLM_TIMEOUT";
    public static final String LLM_RATE_LIMITED = "LLM_RATE_LIMITED";
    public static final String LLM_PROVIDER_UNAVAILABLE = "LLM_PROVIDER_UNAVAILABLE";
    public static final String LLM_ALL_PROVIDERS_FAILED = "LLM_ALL_PROVIDERS_FAILED";
    public static final String LLM_EMPTY_RESPONSE = "LLM_EMPTY_RESPONSE";

    private final String errorCode;
    private final String auditRawResponse;
    private final String provider;
    private final String model;
    private final Integer retryAfterSeconds;
    private final Integer timeoutSeconds;
    private final Integer promptChars;

    public AiProviderFailureException(String errorCode, String message, String auditRawResponse, Throwable cause) {
        this(errorCode, message, auditRawResponse, cause, null, null, null, null, null);
    }

    public AiProviderFailureException(
            String errorCode,
            String message,
            String auditRawResponse,
            Throwable cause,
            String provider,
            String model,
            Integer retryAfterSeconds,
            Integer timeoutSeconds,
            Integer promptChars) {
        super("[" + errorCode + "] " + message, cause);
        this.errorCode = errorCode;
        this.auditRawResponse = auditRawResponse;
        this.provider = provider;
        this.model = model;
        this.retryAfterSeconds = retryAfterSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.promptChars = promptChars;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getAuditRawResponse() {
        return auditRawResponse;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public Integer getPromptChars() {
        return promptChars;
    }

    public static AiProviderFailureException timeout(
            String provider,
            String model,
            int timeoutSeconds,
            int promptChars,
            Throwable cause) {
        String message = provider + " " + model + " timed out after " + timeoutSeconds
                + "s (promptChars=" + promptChars + ").";
        return new AiProviderFailureException(
                LLM_TIMEOUT, message, null, cause, provider, model, null, timeoutSeconds, promptChars);
    }

    public static AiProviderFailureException rateLimited(
            String provider,
            String model,
            Integer retryAfterSeconds,
            Throwable cause) {
        String retryText = retryAfterSeconds == null ? "" : ", retry after " + retryAfterSeconds + "s";
        String message = provider + " quota/rate limit exceeded for " + model + retryText + ".";
        return new AiProviderFailureException(
                LLM_RATE_LIMITED, message, null, cause, provider, model, retryAfterSeconds, null, null);
    }

    public static AiProviderFailureException unavailable(
            String provider,
            String model,
            String detail,
            Throwable cause) {
        String message = provider + " " + model + " unavailable: " + safeDetail(detail);
        return new AiProviderFailureException(
                LLM_PROVIDER_UNAVAILABLE, message, null, cause, provider, model, null, null, null);
    }

    public static AiProviderFailureException emptyResponse(String provider, String model, Throwable cause) {
        String message = provider + " " + model + " returned an empty response.";
        return new AiProviderFailureException(
                LLM_EMPTY_RESPONSE, message, null, cause, provider, model, null, null, null);
    }

    public static AiProviderFailureException allProvidersFailed(String message, Throwable cause) {
        return new AiProviderFailureException(LLM_ALL_PROVIDERS_FAILED, message, null, cause);
    }

    private static String safeDetail(String detail) {
        return detail == null || detail.isBlank() ? "unknown error" : detail;
    }
}
