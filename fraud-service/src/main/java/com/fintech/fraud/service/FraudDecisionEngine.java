package com.fintech.fraud.service;

import com.fintech.fraud.client.MlFraudScoringClient;
import com.fintech.fraud.client.MlScoringException;
import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.dto.FraudPredictionResponse;
import com.fintech.fraud.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Multi-Tier Java Fraud Decision Engine.
 * Combines hard business rules, currency eligibility, and calibrated ML scoring.
 *
 * Corrected Decision Ordering:
 * 1. Feature Extraction: Extracts features strictly from prior records in [windowStart, t), excluding currentId.
 * 2. Currency-Independent Velocity Spike: Catches burst testing/attacks in ANY currency (> 3 prior txns in 60s).
 * 3. Currency Eligibility Guard: Checks supported currency (USD). Unsupported currencies (e.g. INR) route safely to MANUAL_REVIEW.
 * 4. USD-Specific High-Value Anomaly: Evaluates amount > $10,000.00 ONLY on supported USD currency (never applies USD limit directly to INR).
 * 5. ML Scoring & Experimental Threshold: Gated against calibrated threshold (default 0.14).
 * 6. Safe Fallback: ML service failures routed strictly to MANUAL_REVIEW (never auto-approved).
 */
@Service
public class FraudDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(FraudDecisionEngine.class);

    private final BigDecimal maxSingleTransactionLimit;
    private final int maxVelocityCount;
    private final double mlFraudThreshold;
    private final String fallbackStatus;
    private final TransactionFeatureExtractor featureExtractor;
    private final MlFraudScoringClient mlClient;

    public FraudDecisionEngine(
            @Value("${fraud.rules.max-single-transaction-amount:10000.00}") BigDecimal maxSingleTransactionLimit,
            @Value("${fraud.rules.max-velocity-count:3}") int maxVelocityCount,
            @Value("${fraud.ml-service.threshold:0.14}") double mlFraudThreshold,
            @Value("${fraud.ml-service.fallback-status:MANUAL_REVIEW}") String fallbackStatus,
            TransactionFeatureExtractor featureExtractor,
            MlFraudScoringClient mlClient) {
        this.maxSingleTransactionLimit = maxSingleTransactionLimit;
        this.maxVelocityCount = maxVelocityCount;
        this.mlFraudThreshold = mlFraudThreshold;
        this.fallbackStatus = fallbackStatus;
        this.featureExtractor = featureExtractor;
        this.mlClient = mlClient;
    }

    /**
     * Evaluates a transaction through the complete multi-tier decision hierarchy.
     */
    public FraudDecisionResult evaluate(Transaction transaction) {
        Long txId = transaction.getId();
        String accountId = transaction.getAccountId();
        BigDecimal amount = transaction.getAmount();
        String currency = transaction.getCurrency();

        // -------------------------------------------------------------
        // Step 1: Feature Extraction (queries prior history in [windowStart, t), excluding currentId)
        // -------------------------------------------------------------
        FraudPredictionRequest features = featureExtractor.extractFeatures(transaction);

        // -------------------------------------------------------------
        // Step 2: Currency-Independent Rule — Velocity Spike (> 3 prior txns in 60s)
        // High-frequency burst testing or bot spam is suspicious regardless of currency.
        // -------------------------------------------------------------
        if (features.velocity60s() > maxVelocityCount) {
            log.warn("❌ FRAUD DETECTED [Velocity Spike] for Account '{}': {} prior txns within 60s (Limit: {})",
                    accountId, features.velocity60s(), maxVelocityCount);
            return new FraudDecisionResult(
                    "FRAUD_DETECTED",
                    "VELOCITY_SPIKE_EXCEEDED (" + features.velocity60s() + " txns in 60s)",
                    null,
                    null
            );
        }

        // -------------------------------------------------------------
        // Step 3: Currency Validation Guard (USD-trained model & USD rules)
        // Checked BEFORE applying USD-specific amount thresholds so that non-USD amounts
        // (such as INR 25,000) are not incorrectly evaluated against a $10,000 USD limit!
        // -------------------------------------------------------------
        if (!featureExtractor.isSupportedCurrency(currency)) {
            log.warn("⚠️ Transaction #{} currency '{}' is unsupported by USD rules/model. Routing to {}",
                    txId, currency, fallbackStatus);
            return new FraudDecisionResult(
                    fallbackStatus,
                    "UNSUPPORTED_CURRENCY_REQUIRES_MANUAL_REVIEW (Currency: " + currency + ")",
                    null,
                    null
            );
        }

        // -------------------------------------------------------------
        // Step 4: USD-Specific Hard Rule — High-Value Anomaly (> $10,000.00 USD)
        // Evaluated ONLY for supported USD transactions.
        // -------------------------------------------------------------
        if (amount != null && amount.compareTo(maxSingleTransactionLimit) > 0) {
            log.warn("❌ FRAUD DETECTED [High-Value Anomaly] on Txn #{}: Amount ${} USD exceeds limit ${} USD",
                    txId, amount, maxSingleTransactionLimit);
            return new FraudDecisionResult(
                    "FRAUD_DETECTED",
                    "HIGH_VALUE_THRESHOLD_EXCEEDED (Amount: $" + amount + ")",
                    null,
                    null
            );
        }

        // -------------------------------------------------------------
        // Step 5: ML Probability Inference & Experimental Threshold Evaluation
        // -------------------------------------------------------------
        try {
            FraudPredictionResponse mlResponse = mlClient.scoreTransaction(features);
            double prob = mlResponse.fraudProbability();
            String version = mlResponse.modelVersion();

            if (prob >= mlFraudThreshold) {
                log.warn("❌ FRAUD DETECTED [ML High Risk] on Txn #{}: Probability {} >= Threshold {} (Model: {})",
                        txId, prob, mlFraudThreshold, version);
                return new FraudDecisionResult(
                        "FRAUD_DETECTED",
                        String.format("ML_HIGH_RISK_DETECTED (Probability: %.4f, Threshold: %.2f, Model: %s)",
                                prob, mlFraudThreshold, version),
                        prob,
                        version
                );
            } else {
                log.info("✅ Transaction #{} passed all fraud rules and ML threshold. Status: APPROVED (ML Score: {})",
                        txId, prob);
                return new FraudDecisionResult(
                        "APPROVED",
                        String.format("VERIFIED_CLEAN (ML Score: %.4f, Model: %s)", prob, version),
                        prob,
                        version
                );
            }

        } catch (MlScoringException e) {
            // Fail-closed policy: Never automatically approve a transaction when ML is unavailable
            log.error("⚠️ ML scoring failure on Txn #{}: [{}]. Routing to safe status '{}'",
                    txId, e.getCategory(), fallbackStatus);
            return new FraudDecisionResult(
                    fallbackStatus,
                    "ML_SERVICE_UNAVAILABLE (" + e.getCategory() + ": " + e.getMessage() + ")",
                    null,
                    null
            );
        } catch (Exception e) {
            log.error("⚠️ Unexpected error during ML scoring on Txn #{}. Routing to safe status '{}'",
                    txId, fallbackStatus, e);
            return new FraudDecisionResult(
                    fallbackStatus,
                    "ML_SERVICE_UNAVAILABLE (UNEXPECTED: " + e.getMessage() + ")",
                    null,
                    null
            );
        }
    }
}
