package com.fintech.fraud.service;

import com.fintech.fraud.exception.RetryableTransactionException;
import com.fintech.fraud.model.Transaction;
import com.fintech.fraud.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Fraud Detection Service - Consumer Reliability & Idempotency Tests")
class FraudDetectionConsumerTest {

    private TransactionRepository repository;
    private FraudDecisionEngine decisionEngine;
    private FraudDetectionConsumer consumer;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TransactionRepository.class);
        decisionEngine = Mockito.mock(FraudDecisionEngine.class);
        consumer = new FraudDetectionConsumer(repository, decisionEngine);
    }

    private Transaction createSampleTransaction(Long id, String status) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setAccountId("ACC-TEST-100");
        tx.setAmount(BigDecimal.valueOf(150.00));
        tx.setCurrency("USD");
        tx.setStatus(status);
        tx.setTimestamp(LocalDateTime.of(2026, 11, 24, 12, 0, 0));
        return tx;
    }

    @Test
    @DisplayName("Reliability 1: Missing transaction in DB triggers RetryableTransactionException for Kafka backoff")
    void shouldThrowRetryableExceptionWhenTransactionNotFound() {
        Long txId = 999L;

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(0);
        when(repository.findById(txId)).thenReturn(Optional.empty());

        Map<String, Object> event = Map.of("id", txId);

        RetryableTransactionException ex = assertThrows(
                RetryableTransactionException.class,
                () -> consumer.consumeTransactionEvent(event)
        );

        assertEquals(txId, ex.getTransactionId());
        assertTrue(ex.getMessage().contains("not found in database"));

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(repository, times(1)).findById(txId);
        verify(decisionEngine, never()).evaluate(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Reliability 1: Claim succeeds but record missing in DB triggers RetryableTransactionException")
    void shouldThrowRetryableExceptionWhenClaimSucceededButRecordMissing() {
        Long txId = 888L;

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(1);
        when(repository.findById(txId)).thenReturn(Optional.empty());

        Map<String, Object> event = Map.of("id", txId);

        RetryableTransactionException ex = assertThrows(
                RetryableTransactionException.class,
                () -> consumer.consumeTransactionEvent(event)
        );

        assertEquals(txId, ex.getTransactionId());
        verify(decisionEngine, never()).evaluate(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Reliability 2: Malformed Kafka event without 'id' throws IllegalArgumentException (DLT candidate)")
    void shouldThrowIllegalArgumentExceptionOnMissingId() {
        Map<String, Object> eventWithoutId = Map.of("accountId", "ACC-TEST-100", "amount", 100.0);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consumeTransactionEvent(eventWithoutId)
        );

        assertTrue(ex.getMessage().contains("missing 'id'"));
        verify(repository, never()).claimTransactionForEvaluation(any());
        verify(decisionEngine, never()).evaluate(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Reliability 2: Malformed Kafka event with non-numeric 'id' throws IllegalArgumentException")
    void shouldThrowIllegalArgumentExceptionOnNonNumericId() {
        Map<String, Object> eventWithInvalidId = Map.of("id", "invalid-id-string");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consumeTransactionEvent(eventWithInvalidId)
        );

        assertTrue(ex.getMessage().contains("invalid non-numeric 'id'"));
        verify(repository, never()).claimTransactionForEvaluation(any());
    }

    @Test
    @DisplayName("Sanitization: Unexpected engine exception persists sanitized 'EVALUATION_ERROR' (no internal details)")
    void shouldPersistSanitizedReasonCodeOnUnexpectedEngineException() {
        Long txId = 108L;
        Transaction tx = createSampleTransaction(txId, "EVALUATING");

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(1);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));
        when(decisionEngine.evaluate(tx)).thenThrow(new RuntimeException("Sensitive schema crash: postgres://admin:secret@host/db"));

        Map<String, Object> event = Map.of("id", txId);
        consumer.consumeTransactionEvent(event);

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(decisionEngine, times(1)).evaluate(tx);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(1)).save(captor.capture());

        Transaction saved = captor.getValue();
        assertEquals("MANUAL_REVIEW", saved.getStatus());
        // Verify strict sanitization: exact code without exception message or credentials
        assertEquals("EVALUATION_ERROR", saved.getFraudReason());
        assertFalse(saved.getFraudReason().contains("secret"));
        assertFalse(saved.getFraudReason().contains("postgres"));
    }

    @Test
    @DisplayName("Reliability 3: Database failure propagates DataAccessException for Kafka retry/backoff")
    void shouldPropagateDataAccessExceptionOnDatabaseFailure() {
        Long txId = 107L;
        Transaction tx = createSampleTransaction(txId, "EVALUATING");

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(1);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));
        when(decisionEngine.evaluate(tx)).thenReturn(new FraudDecisionResult(
                "APPROVED",
                "VERIFIED_CLEAN",
                0.01,
                "1.0.0"
        ));
        when(repository.save(any(Transaction.class)))
                .thenThrow(new DataAccessResourceFailureException("PostgreSQL connection lost"));

        Map<String, Object> event = Map.of("id", txId);

        assertThrows(DataAccessResourceFailureException.class, () -> consumer.consumeTransactionEvent(event));

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(decisionEngine, times(1)).evaluate(tx);
        verify(repository, times(1)).save(tx);
    }

    @Test
    @DisplayName("Idempotency: Suppresses duplicate delivery when transaction is in terminal APPROVED state")
    void shouldSuppressDuplicateWhenTransactionAlreadyApproved() {
        Long txId = 104L;
        Transaction tx = createSampleTransaction(txId, "APPROVED");

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(0);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));

        Map<String, Object> event = Map.of("id", txId, "accountId", "ACC-TEST-100");
        consumer.consumeTransactionEvent(event);

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(repository, times(1)).findById(txId);
        verify(decisionEngine, never()).evaluate(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Idempotency: Suppresses duplicate delivery when transaction is in terminal FRAUD_DETECTED state")
    void shouldSuppressDuplicateWhenTransactionAlreadyFraudDetected() {
        Long txId = 105L;
        Transaction tx = createSampleTransaction(txId, "FRAUD_DETECTED");

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(0);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));

        Map<String, Object> event = Map.of("id", txId, "accountId", "ACC-TEST-100");
        consumer.consumeTransactionEvent(event);

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(decisionEngine, never()).evaluate(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Concurrency: Skips execution when transaction is currently EVALUATING")
    void shouldSkipWhenTransactionIsCurrentlyEvaluating() {
        Long txId = 106L;
        Transaction tx = createSampleTransaction(txId, "EVALUATING");

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(0);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));

        Map<String, Object> event = Map.of("id", txId);
        consumer.consumeTransactionEvent(event);

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(decisionEngine, never()).evaluate(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Standard Flow: Atomically claims and persists APPROVED decision")
    void shouldClaimAndPersistApprovedDecision() {
        Long txId = 101L;
        Transaction tx = createSampleTransaction(txId, "EVALUATING");

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(1);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));
        when(decisionEngine.evaluate(tx)).thenReturn(new FraudDecisionResult(
                "APPROVED",
                "VERIFIED_CLEAN (ML Score: 0.0350, Model: 1.0.0)",
                0.035,
                "1.0.0"
        ));

        Map<String, Object> event = Map.of("id", txId, "accountId", "ACC-TEST-100", "amount", 150.00);
        consumer.consumeTransactionEvent(event);

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(decisionEngine, times(1)).evaluate(tx);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(1)).save(captor.capture());

        Transaction saved = captor.getValue();
        assertEquals("APPROVED", saved.getStatus());
        assertTrue(saved.getFraudReason().contains("VERIFIED_CLEAN"));
    }

    @Test
    @DisplayName("Standard Flow: Atomically claims and persists FRAUD_DETECTED decision")
    void shouldClaimAndPersistFraudDetectedDecision() {
        Long txId = 102L;
        Transaction tx = createSampleTransaction(txId, "EVALUATING");

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(1);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));
        when(decisionEngine.evaluate(tx)).thenReturn(new FraudDecisionResult(
                "FRAUD_DETECTED",
                "ML_HIGH_RISK_DETECTED (Probability: 0.7850, Threshold: 0.14, Model: 1.0.0)",
                0.785,
                "1.0.0"
        ));

        Map<String, Object> event = Map.of("id", txId, "accountId", "ACC-TEST-100", "amount", 150.00);
        consumer.consumeTransactionEvent(event);

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(decisionEngine, times(1)).evaluate(tx);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(1)).save(captor.capture());

        Transaction saved = captor.getValue();
        assertEquals("FRAUD_DETECTED", saved.getStatus());
        assertTrue(saved.getFraudReason().contains("ML_HIGH_RISK_DETECTED"));
    }

    @Test
    @DisplayName("Standard Flow: Atomically claims and persists MANUAL_REVIEW fallback decision")
    void shouldClaimAndPersistManualReviewDecision() {
        Long txId = 103L;
        Transaction tx = createSampleTransaction(txId, "EVALUATING");

        when(repository.claimTransactionForEvaluation(txId)).thenReturn(1);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));
        when(decisionEngine.evaluate(tx)).thenReturn(new FraudDecisionResult(
                "MANUAL_REVIEW",
                "ML_SERVICE_UNAVAILABLE (TIMEOUT: ML service request timed out)",
                null,
                null
        ));

        Map<String, Object> event = Map.of("id", txId, "accountId", "ACC-TEST-100", "amount", 150.00);
        consumer.consumeTransactionEvent(event);

        verify(repository, times(1)).claimTransactionForEvaluation(txId);
        verify(decisionEngine, times(1)).evaluate(tx);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(1)).save(captor.capture());

        Transaction saved = captor.getValue();
        assertEquals("MANUAL_REVIEW", saved.getStatus());
        assertTrue(saved.getFraudReason().contains("ML_SERVICE_UNAVAILABLE"));
    }
}
