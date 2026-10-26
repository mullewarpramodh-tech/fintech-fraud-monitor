package com.fintech.fraud.service;

import com.fintech.fraud.model.Transaction;
import com.fintech.fraud.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
public class FraudDetectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionConsumer.class);

    // Fraud Detection Rule Constants
    private static final double MAX_SINGLE_TRANSACTION_LIMIT = 10000.0;
    private static final int MAX_VELOCITY_TX_COUNT = 3; // Maximum allowable transactions...
    private static final int VELOCITY_WINDOW_SECONDS = 60; // ...within any 60-second sliding window

    private final TransactionRepository repository;

    public FraudDetectionConsumer(TransactionRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "transaction-events", groupId = "fraud-detection-group")
    public void consumeTransactionEvent(Map<String, Object> transactionEvent) {
        log.info("🚨 [Kafka Ingestion] Received transaction event for fraud analysis: {}", transactionEvent);

        try {
            Long transactionId = Long.parseLong(transactionEvent.get("id").toString());
            String accountId = (String) transactionEvent.get("accountId");
            double amount = Double.parseDouble(transactionEvent.get("amount").toString());

            Optional<Transaction> dbRecord = repository.findById(transactionId);
            if (dbRecord.isEmpty()) {
                log.warn("Transaction ID {} not found in database. Skipping analysis.", transactionId);
                return;
            }

            Transaction transaction = dbRecord.get();

            // 1. Sliding-Window Calculation: Look back 60 seconds from current time
            LocalDateTime windowStart = LocalDateTime.now().minusSeconds(VELOCITY_WINDOW_SECONDS);
            long recentTxCount = repository.countByAccountIdAndTimestampAfter(accountId, windowStart);

            log.info("📊 Account '{}' velocity check: {} transactions in the last {} seconds.",
                    accountId, recentTxCount, VELOCITY_WINDOW_SECONDS);

            // 2. Evaluate Multi-Tier Fraud Detection Rules
            if (amount > MAX_SINGLE_TRANSACTION_LIMIT) {
                // Rule 1: High-Value Anomaly Detection
                log.warn("❌ FRAUD DETECTED [High-Value Anomaly] on Transaction #{}: Amount ${} exceeds limit of ${}",
                        transactionId, amount, MAX_SINGLE_TRANSACTION_LIMIT);
                transaction.setStatus("FRAUD_DETECTED");
                transaction.setFraudReason("HIGH_VALUE_THRESHOLD_EXCEEDED (Amount: $" + amount + ")");

            } else if (recentTxCount > MAX_VELOCITY_TX_COUNT) {
                // Rule 2: Sliding-Window Velocity Spike Detection
                log.warn("❌ FRAUD DETECTED [Velocity Spike] for Account '{}': {} transactions within {}s window (Limit: {})",
                        accountId, recentTxCount, VELOCITY_WINDOW_SECONDS, MAX_VELOCITY_TX_COUNT);
                transaction.setStatus("FRAUD_DETECTED");
                transaction.setFraudReason("VELOCITY_SPIKE_EXCEEDED (" + recentTxCount + " txns in " + VELOCITY_WINDOW_SECONDS + "s)");

            } else {
                // Rule 3: Verified Clean Transaction
                log.info("✅ Transaction #{} for Account '{}' passed all fraud checks. Status: APPROVED",
                        transactionId, accountId);
                transaction.setStatus("APPROVED");
                transaction.setFraudReason("VERIFIED_CLEAN");
            }

            // 3. Persist verified status and fraud audit reason into Neon PostgreSQL
            repository.save(transaction);
            log.info("💾 [Neon DB] Successfully persisted Transaction #{} with Status: '{}' and Reason: '{}'",
                    transactionId, transaction.getStatus(), transaction.getFraudReason());

        } catch (Exception e) {
            log.error("❌ Error processing fraud detection event: {}", e.getMessage(), e);
        }
    }
}
