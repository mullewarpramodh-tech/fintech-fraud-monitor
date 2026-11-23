package com.fintech.fraud.client;

/**
 * Structured runtime exception capturing ML service communication and response validation failures.
 * Encapsulates failure category for safe routing by the decision engine.
 */
public class MlScoringException extends RuntimeException {

    public enum ErrorCategory {
        CONNECTION_FAILED,
        TIMEOUT,
        SERVER_ERROR,
        CLIENT_ERROR,
        INVALID_RESPONSE,
        UNEXPECTED
    }

    private final ErrorCategory category;

    public MlScoringException(ErrorCategory category, String message) {
        super(message);
        this.category = category;
    }

    public MlScoringException(ErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public ErrorCategory getCategory() {
        return category;
    }
}
