package com.fintech.fraud.exception;

/**
 * Exception thrown when a transaction event arrives before the database record is visible
 * or when a transient out-of-order Kafka delivery occurs.
 * Configured as a retryable exception with bounded backoff in Kafka error handling.
 */
public class RetryableTransactionException extends RuntimeException {

    private final Long transactionId;

    public RetryableTransactionException(Long transactionId, String message) {
        super(message);
        this.transactionId = transactionId;
    }

    public Long getTransactionId() {
        return transactionId;
    }
}
