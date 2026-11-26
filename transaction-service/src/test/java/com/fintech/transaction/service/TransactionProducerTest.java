package com.fintech.transaction.service;

import com.fintech.transaction.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Transaction Service - Kafka Producer Unit Tests")
class TransactionProducerTest {

    static class RecordingKafkaTemplate extends KafkaTemplate<String, Transaction> {
        String sentTopic;
        String sentKey;
        Transaction sentData;

        RecordingKafkaTemplate() {
            super(new DefaultKafkaProducerFactory<>(Map.of()));
        }

        @Override
        public CompletableFuture<SendResult<String, Transaction>> send(String topic, String key, Transaction data) {
            this.sentTopic = topic;
            this.sentKey = key;
            this.sentData = data;
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    @DisplayName("Should publish transaction event to 'transaction-events' using accountId as message key")
    void shouldPublishTransactionEventWithAccountIdKey() {
        RecordingKafkaTemplate recordingTemplate = new RecordingKafkaTemplate();
        TransactionProducer producer = new TransactionProducer(recordingTemplate);

        Transaction tx = new Transaction();
        tx.setId(101L);
        tx.setAccountId("ACC-12345");
        tx.setAmount(BigDecimal.valueOf(250.00));
        tx.setCurrency("USD");
        tx.setStatus("PENDING");
        tx.setTimestamp(LocalDateTime.of(2026, 11, 24, 14, 0, 0));

        producer.sendTransactionEvent(tx);

        assertEquals("transaction-events", recordingTemplate.sentTopic);
        assertEquals("ACC-12345", recordingTemplate.sentKey);
        assertSame(tx, recordingTemplate.sentData);
    }
}
