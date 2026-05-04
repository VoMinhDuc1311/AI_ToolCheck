package com.aitoolcheck.ai_toolcheck1_backend.exception;

/**
 * Exception thrown when the AI (Gemini) response cannot be parsed into valid JSON,
 * or when the resulting DTO fails structural/validation checks.
 *
 * <h3>Error Classification via {@link ErrorType}</h3>
 * <ul>
 *   <li>{@link ErrorType#INVALID_JSON_SYNTAX} – raw input has no parseable JSON.</li>
 *   <li>{@link ErrorType#DTO_MAPPING_ERROR} – Jackson cannot map JSON → DTO.</li>
 *   <li>{@link ErrorType#DTO_VALIDATION_FAILED} – DTO violates Jakarta constraints.</li>
 * </ul>
 *
 * <p>Message format: {@code [AiJsonParser][{ErrorType}] {message}}</p>
 */
public class AiJsonParseException extends RuntimeException {

    // ─── Error Type Enum ─────────────────────────────────────────────────────

    /**
     * Classifies the root cause of a parsing failure.
     * Used by upstream consumers (e.g. RabbitMQ DLQ handler, retry logic)
     * to decide the appropriate recovery strategy.
     */
    public enum ErrorType {

        /** Layer 1: No valid '{' / '[' boundaries found in raw AI response. */
        INVALID_JSON_SYNTAX,

        /** Layer 4: Jackson readValue() failed – structural mismatch or bad JSON. */
        DTO_MAPPING_ERROR,

        /** Layer 5: DTO passed Jackson but violates @NotNull / @NotEmpty constraints. */
        DTO_VALIDATION_FAILED
    }

    // ─── Fields ──────────────────────────────────────────────────────────────

    private final ErrorType errorType;

    // ─── Constructors ─────────────────────────────────────────────────────────

    /**
     * Primary constructor — enforces message format.
     *
     * @param errorType Classification of the failure (never null).
     * @param message   Human-readable description of what went wrong.
     */
    public AiJsonParseException(ErrorType errorType, String message) {
        super(formatMessage(errorType, message));
        this.errorType = errorType;
    }

    /**
     * Secondary constructor for wrapping a root cause.
     *
     * @param errorType Classification of the failure (never null).
     * @param message   Human-readable description of what went wrong.
     * @param cause     The original exception that triggered this failure.
     */
    public AiJsonParseException(ErrorType errorType, String message, Throwable cause) {
        super(formatMessage(errorType, message), cause);
        this.errorType = errorType;
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    /** Returns the classification of this error for use in retry / DLQ routing. */
    public ErrorType getErrorType() {
        return errorType;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Produces the canonical message format:
     * {@code [AiJsonParser][INVALID_JSON_SYNTAX] No JSON delimiters found.}
     */
    private static String formatMessage(ErrorType errorType, String message) {
        return "[AiJsonParser][" + errorType.name() + "] " + message;
    }
}

