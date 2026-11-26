package com.fintech.fraud.service;

import com.fintech.fraud.exception.RetryableTransactionException;
import com.fintech.fraud.model.Transaction;
import com.fintech.fraud.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Kafka Event Consumer for real-time Fraud Detection.
 *
 * Implements atomic idempotency, safe duplicate delivery suppression,
 * bounded retries on transient replication lag, durable error routing,
 * and delegates scoring to the multi-tier FraudDecisionEngine.
 */
@Service
public class FraudDetectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionConsumer.class);

    private final TransactionRepository repository;
    private final FraudDecisionEngine decisionEngine;

    public FraudDetectionConsumer(TransactionRepository repository, FraudDecisionEngine decisionEngine) {
        this.repository = repository;
        this.decisionEngine = decisionEngine;
    }

    @KafkaListener(topics = "transaction-events", groupId = "fraud-detection-group")
    public void consumeTransactionEvent(Map<String, Object> transactionEvent) {
        log.info("🚨 [Kafka Ingestion] Received transaction event: {}", transactionEvent);

        // Malformed messages throw IllegalArgumentException (routed directly to DLT without retrying)
        Long transactionId = extractTransactionId(transactionEvent);

        try {
            // 1. Atomically claim PENDING -> EVALUATING
            int claimed = repository.claimTransactionForEvaluation(transactionId);

            if (claimed == 0) {
                handleUnclaimedTransaction(transactionId);
                return;
            }

            log.info("🔒 Successfully claimed Transaction #{} for evaluation (PENDING -> EVALUATING)", transactionId);

            // 2. Retrieve claimed transaction from database
            Optional<Transaction> dbRecord = repository.findById(transactionId);
            if (dbRecord.isEmpty()) {
                log.error("❌ Transaction #{} was claimed but not found in database. Triggering retry.", transactionId);
                throw new RetryableTransactionException(
                        transactionId,
                        "Transaction #" + transactionId + " claimed but not found in database"
                );
            }

            Transaction transaction = dbRecord.get();

            // 3. Delegate to FraudDecisionEngine
            FraudDecisionResult decision;
            try {
                decision = decisionEngine.evaluate(transaction);
            } catch (Exception e) {
                // Sanitize unexpected error details: log full diagnostic trace internally,
                // but persist only a stable, clean reason code (EVALUATION_ERROR) to PostgreSQL
                log.error("⚠️ Unexpected failure during fraud evaluation of Transaction #{}. Routing to MANUAL_REVIEW",
                        transactionId, e);
                decision = new FraudDecisionResult(
                        "MANUAL_REVIEW",
                        "EVALUATION_ERROR",
                        null,
                        null
                );
            }

            // 4. Persist evaluated status and audit reason
            transaction.setStatus(decision.status());
            transaction.setFraudReason(decision.fraudReason());
            repository.save(transaction);

            log.info("💾 [Neon DB] Successfully persisted Transaction #{} with Status: '{}' and Reason: '{}'",
                    transactionId, transaction.getStatus(), transaction.getFraudReason());

        } catch (DataAccessException e) {
            log.error("❌ Database persistence failure while processing Transaction #{}: {}", transactionId, e.getMessage(), e);
            throw e; // Rethrow so Kafka container triggers bounded retries / DLT
        }
    }

    private void handleUnclaimedTransaction(Long transactionId) {
        Optional<Transaction> existingOpt = repository.findById(transactionId);
        if (existingOpt.isEmpty()) {
            // Potential race condition: Kafka event arrived before PostgreSQL commit visibility.
            // Throw RetryableTransactionException so Kafka retries with bounded backoff rather than dropping.
            log.warn("⚠️ Transaction #{} not found in database. Possible out-of-order event or commit lag. Triggering retry.",
                    transactionId);
            throw new RetryableTransactionException(
                    transactionId,
                    "Transaction #" + transactionId + " not found in database (event may precede DB commit)"
            );
        }

        Transaction existing = existingOpt.get();
        String currentStatus = existing.getStatus();

        if (isTerminalStatus(currentStatus)) {
            log.info("ℹ️ Transaction #{} already finalized with status '{}'. Duplicate Kafka event safely ignored.",
                    transactionId, currentStatus);
            return;
        }

        if ("EVALUATING".equals(currentStatus)) {
            log.warn("⚠️ Transaction #{} is currently in EVALUATING state by another worker. Skipping concurrent redelivery.",
                    transactionId);
            return;
        }

        log.warn("⚠️ Transaction #{} in unclaimable status '{}'. Skipping event.", transactionId, currentStatus);
    }

    private boolean isTerminalStatus(String status) {
        return "APPROVED".equalsIgnoreCase(status)
                || "FRAUD_DETECTED".equalsIgnoreCase(status)
                || "MANUAL_REVIEW".equalsIgnoreCase(status);
    }

    private Long extractTransactionId(Map<String, Object> transactionEvent) {
        if (transactionEvent == null || !transactionEvent.containsKey("id")) {
            throw new IllegalArgumentException("Malformed Kafka event: missing 'id' payload");
        }
        Object idObj = transactionEvent.get("id");
        if (idObj == null) {
            throw new IllegalArgumentException("Malformed Kafka event: 'id' attribute is null");
        }
        if (idObj instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(idObj.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed Kafka event: invalid non-numeric 'id' value: " + idObj);
        }
    }
}
