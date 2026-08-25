package com.fintech.fraud.service;

import com.fintech.fraud.model.Transaction;
import com.fintech.fraud.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Optional;

@Service
public class FraudDetectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionConsumer.class);
    private final TransactionRepository repository;

    public FraudDetectionConsumer(TransactionRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "transaction-events", groupId = "fraud-detection-group")
    public void consumeTransactionEvent(Map<String, Object> transactionEvent) {
        log.info("🚨 KAFKA RECEIVED: Analyzing transaction: {}", transactionEvent);

        try {
            Long transactionId = Long.parseLong(transactionEvent.get("id").toString());
            double amount = Double.parseDouble(transactionEvent.get("amount").toString());
            
            // 1. Find the transaction in the database
            Optional<Transaction> dbRecord = repository.findById(transactionId);
            
            if (dbRecord.isPresent()) {
                Transaction transaction = dbRecord.get();
                
                // 2. Fraud Logic (Amount > 10,000)
                if (amount > 10000) {
                    log.warn("❌ FRAUD DETECTED for ID {}: Amount {} exceeds limit!", transactionId, amount);
                    transaction.setStatus("FRAUD_DETECTED");
                } else {
                    log.info("✅ Transaction ID {} is CLEAN. Approving...", transactionId);
                    transaction.setStatus("APPROVED");
                }
                
                // 3. Save the updated status back to Neon Postgres
                repository.save(transaction);
                log.info("Database updated successfully for ID: {}", transactionId);
            }
        } catch (Exception e) {
            log.error("Error processing message", e);
        }
    }
}
