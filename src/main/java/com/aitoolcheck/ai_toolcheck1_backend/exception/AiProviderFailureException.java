package com.aitoolcheck.ai_toolcheck1_backend.exception;

public class AiProviderFailureException extends RuntimeException {

    private final String errorCode;
    private final String auditRawResponse;

    public AiProviderFailureException(String errorCode, String message, String auditRawResponse, Throwable cause) {
        super("[" + errorCode + "] " + message, cause);
        this.errorCode = errorCode;
        this.auditRawResponse = auditRawResponse;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getAuditRawResponse() {
        return auditRawResponse;
    }
}
