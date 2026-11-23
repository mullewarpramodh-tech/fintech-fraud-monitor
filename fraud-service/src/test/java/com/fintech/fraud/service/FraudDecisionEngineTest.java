package com.fintech.fraud.service;

import com.fintech.fraud.client.MlFraudScoringClient;
import com.fintech.fraud.client.MlScoringException;
import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.dto.FraudPredictionResponse;
import com.fintech.fraud.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Fraud Decision Engine — Multi-Tier Hierarchy & Currency Safety Tests")
class FraudDecisionEngineTest {

    private TransactionFeatureExtractor featureExtractor;
    private MlFraudScoringClient mlClient;
    private FraudDecisionEngine decisionEngine;

    @BeforeEach
    void setUp() {
        featureExtractor = Mockito.mock(TransactionFeatureExtractor.class);
        mlClient = Mockito.mock(MlFraudScoringClient.class);

        when(featureExtractor.isSupportedCurrency("USD")).thenReturn(true);
        when(featureExtractor.isSupportedCurrency("INR")).thenReturn(false);
        when(featureExtractor.isSupportedCurrency("EUR")).thenReturn(false);

        decisionEngine = new FraudDecisionEngine(
                new BigDecimal("10000.00"),
                3,
                0.14,
                "MANUAL_REVIEW",
                featureExtractor,
                mlClient
        );
    }

    private FraudPredictionRequest defaultFeatures(int velocity) {
        return new FraudPredictionRequest(
                BigDecimal.valueOf(100.00),
                1,
                BigDecimal.valueOf(100.00),
                1.0,
                velocity,
                0.0,
                1.0
        );
    }

    @Test
    @DisplayName("Ordering 1: Currency-Independent Velocity Spike (> 3 in 60s) flags FRAUD_DETECTED first")
    void shouldTriggerVelocitySpikeHardRuleFirst() {
        Transaction tx = new Transaction("ACC-BURST", BigDecimal.valueOf(50.00), "USD", "PENDING", LocalDateTime.now());
        tx.setId(1L);

        when(featureExtractor.extractFeatures(tx)).thenReturn(defaultFeatures(4)); // velocity = 4 > 3

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("FRAUD_DETECTED", result.status());
        assertTrue(result.fraudReason().contains("VELOCITY_SPIKE_EXCEEDED"));
        assertNull(result.mlProbability());

        verifyNoInteractions(mlClient);
    }

    @Test
    @DisplayName("Ordering 1b: Velocity Spike also catches attacks in unsupported currency (e.g. INR burst)")
    void shouldCatchVelocitySpikeInAnyCurrency() {
        Transaction tx = new Transaction("ACC-INR-BURST", BigDecimal.valueOf(200.00), "INR", "PENDING", LocalDateTime.now());
        tx.setId(2L);

        when(featureExtractor.extractFeatures(tx)).thenReturn(defaultFeatures(5)); // velocity = 5 > 3

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("FRAUD_DETECTED", result.status());
        assertTrue(result.fraudReason().contains("VELOCITY_SPIKE_EXCEEDED"));
        assertNull(result.mlProbability());

        verifyNoInteractions(mlClient);
    }

    @Test
    @DisplayName("Ordering 2: High-Value INR transaction (₹25,000) does NOT trigger USD $10k rule; routes to MANUAL_REVIEW")
    void shouldRouteHighValueInrTransactionToManualReviewNotHighValueFraud() {
        // ₹25,000 INR is numerically > 10,000, but MUST NOT trigger USD $10,000 threshold
        Transaction tx = new Transaction("ACC-INR-HIGH", BigDecimal.valueOf(25000.00), "INR", "PENDING", LocalDateTime.now());
        tx.setId(3L);

        when(featureExtractor.extractFeatures(tx)).thenReturn(defaultFeatures(0)); // velocity = 0

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("MANUAL_REVIEW", result.status(), "Must route to MANUAL_REVIEW due to unsupported currency");
        assertTrue(result.fraudReason().contains("UNSUPPORTED_CURRENCY_REQUIRES_MANUAL_REVIEW"));
        assertFalse(result.fraudReason().contains("HIGH_VALUE_THRESHOLD_EXCEEDED"),
                "Must not apply USD threshold directly to INR amounts");
        assertNull(result.mlProbability());

        verifyNoInteractions(mlClient);
    }

    @Test
    @DisplayName("Ordering 3: USD-Specific High-Value Anomaly (> $10k USD) flags FRAUD_DETECTED")
    void shouldTriggerHighValueHardRuleForUsd() {
        Transaction tx = new Transaction("ACC-USD-HIGH", BigDecimal.valueOf(15000.00), "USD", "PENDING", LocalDateTime.now());
        tx.setId(4L);

        when(featureExtractor.extractFeatures(tx)).thenReturn(defaultFeatures(0)); // velocity = 0

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("FRAUD_DETECTED", result.status());
        assertTrue(result.fraudReason().contains("HIGH_VALUE_THRESHOLD_EXCEEDED"));
        assertNull(result.mlProbability());

        verifyNoInteractions(mlClient);
    }

    @Test
    @DisplayName("Ordering 4: ML Probability >= 0.14 flags FRAUD_DETECTED on USD transaction")
    void shouldFlagFraudWhenMlProbabilityExceedsThreshold() {
        Transaction tx = new Transaction("ACC-USD-01", BigDecimal.valueOf(450.00), "USD", "PENDING", LocalDateTime.now());
        tx.setId(5L);

        FraudPredictionRequest features = defaultFeatures(1);
        when(featureExtractor.extractFeatures(tx)).thenReturn(features);
        when(mlClient.scoreTransaction(features)).thenReturn(new FraudPredictionResponse(0.8520, "v1.0.0"));

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("FRAUD_DETECTED", result.status());
        assertTrue(result.fraudReason().contains("ML_HIGH_RISK_DETECTED"));
        assertEquals(0.8520, result.mlProbability());
        assertEquals("v1.0.0", result.mlModelVersion());
    }

    @Test
    @DisplayName("Ordering 4: ML Probability < 0.14 marks clean USD transaction APPROVED")
    void shouldApproveCleanTransactionWhenMlProbabilityBelowThreshold() {
        Transaction tx = new Transaction("ACC-USD-02", BigDecimal.valueOf(75.00), "USD", "PENDING", LocalDateTime.now());
        tx.setId(6L);

        FraudPredictionRequest features = defaultFeatures(0);
        when(featureExtractor.extractFeatures(tx)).thenReturn(features);
        when(mlClient.scoreTransaction(features)).thenReturn(new FraudPredictionResponse(0.0210, "v1.0.0"));

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("APPROVED", result.status());
        assertTrue(result.fraudReason().contains("VERIFIED_CLEAN"));
        assertEquals(0.0210, result.mlProbability());
        assertEquals("v1.0.0", result.mlModelVersion());
    }

    @Test
    @DisplayName("Ordering 5 (Fail-Safe): ML Timeout routes strictly to MANUAL_REVIEW (never APPROVED)")
    void shouldRouteToManualReviewOnMlTimeout() {
        Transaction tx = new Transaction("ACC-USD-03", BigDecimal.valueOf(100.00), "USD", "PENDING", LocalDateTime.now());
        tx.setId(7L);

        FraudPredictionRequest features = defaultFeatures(0);
        when(featureExtractor.extractFeatures(tx)).thenReturn(features);
        when(mlClient.scoreTransaction(features))
                .thenThrow(new MlScoringException(MlScoringException.ErrorCategory.TIMEOUT, "Read timed out"));

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("MANUAL_REVIEW", result.status(), "Must never auto-approve on ML timeout");
        assertTrue(result.fraudReason().contains("ML_SERVICE_UNAVAILABLE"));
        assertTrue(result.fraudReason().contains("TIMEOUT"));
    }

    @Test
    @DisplayName("Ordering 5 (Fail-Safe): ML Connection Failure routes strictly to MANUAL_REVIEW")
    void shouldRouteToManualReviewOnMlConnectionFailure() {
        Transaction tx = new Transaction("ACC-USD-04", BigDecimal.valueOf(100.00), "USD", "PENDING", LocalDateTime.now());
        tx.setId(8L);

        FraudPredictionRequest features = defaultFeatures(0);
        when(featureExtractor.extractFeatures(tx)).thenReturn(features);
        when(mlClient.scoreTransaction(features))
                .thenThrow(new MlScoringException(MlScoringException.ErrorCategory.CONNECTION_FAILED, "Connection refused"));

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("MANUAL_REVIEW", result.status(), "Must never auto-approve on connection failure");
        assertTrue(result.fraudReason().contains("ML_SERVICE_UNAVAILABLE"));
        assertTrue(result.fraudReason().contains("CONNECTION_FAILED"));
    }

    @Test
    @DisplayName("Ordering 5 (Fail-Safe): ML Server Error routes strictly to MANUAL_REVIEW")
    void shouldRouteToManualReviewOnMlServerError() {
        Transaction tx = new Transaction("ACC-USD-05", BigDecimal.valueOf(100.00), "USD", "PENDING", LocalDateTime.now());
        tx.setId(9L);

        FraudPredictionRequest features = defaultFeatures(0);
        when(featureExtractor.extractFeatures(tx)).thenReturn(features);
        when(mlClient.scoreTransaction(features))
                .thenThrow(new MlScoringException(MlScoringException.ErrorCategory.SERVER_ERROR, "HTTP 500"));

        FraudDecisionResult result = decisionEngine.evaluate(tx);

        assertEquals("MANUAL_REVIEW", result.status(), "Must never auto-approve on HTTP 500");
        assertTrue(result.fraudReason().contains("ML_SERVICE_UNAVAILABLE"));
        assertTrue(result.fraudReason().contains("SERVER_ERROR"));
    }
}
