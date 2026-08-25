package com.fintech.transaction.service;

import com.fintech.transaction.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class TransactionProducer {

    private static final Logger log = LoggerFactory.getLogger(TransactionProducer.class);
    private static final String TOPIC = "transaction-events";
    
    private final KafkaTemplate<String, Transaction> kafkaTemplate;

    public TransactionProducer(KafkaTemplate<String, Transaction> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendTransactionEvent(Transaction transaction) {
        log.info("Publishing transaction {} to Kafka topic: {}", transaction.getId(), TOPIC);
        kafkaTemplate.send(TOPIC, transaction.getAccountId(), transaction);
    }
}
