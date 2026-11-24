package com.fintech.fraud.integration;

import com.fintech.fraud.client.MlFraudScoringClient;
import com.fintech.fraud.client.MlScoringException;
import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.dto.FraudPredictionResponse;
import com.fintech.fraud.model.Transaction;
import com.fintech.fraud.repository.TransactionRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-End Integration Test Suite for Fraud Detection Service.
 *
 * Verifies the full transaction processing lifecycle across:
 * - Real in-process Apache Kafka broker (@EmbeddedKafka)
 * - Real asynchronous Kafka producer & consumer event delivery
 * - Isolated in-memory PostgreSQL (H2 in PostgreSQL mode)
 * - Multi-tier decision engine rules & ML inference boundary
 * - Idempotency, duplicate event suppression, retries, and DLT routing
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction-events", "transaction-events-dlt"}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("End-to-End Integration Tests — Kafka, PostgreSQL (H2) & Fraud Decision Engine")
class FraudDetectionIntegrationTest {

    private static final String TOPIC = "transaction-events";
    private static final String DLT_TOPIC = "transaction-events-dlt";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private TransactionRepository repository;

    @MockBean
    private MlFraudScoringClient mlClient;

    private Consumer<String, String> dltConsumer;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        reset(mlClient);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "dlt-test-group-" + System.currentTimeMillis(),
                "true",
                embeddedKafkaBroker
        );
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps);
        dltConsumer = consumerFactory.createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, DLT_TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (dltConsumer != null) {
            dltConsumer.close();
        }
    }

    @Test
    @DisplayName("Scenario 1: Valid USD transaction reaches final decision (APPROVED via ML inference)")
    void validUsdTransactionShouldBeApproved() throws Exception {
        // Given a clean PENDING transaction in DB
        Transaction tx = repository.save(new Transaction(
                "ACC-VALID-001",
                BigDecimal.valueOf(150.00),
                "USD",
                "PENDING",
                LocalDateTime.now()
        ));

        // Stub ML service returning low fraud probability (below threshold 0.14)
        when(mlClient.scoreTransaction(any(FraudPredictionRequest.class)))
                .thenReturn(new FraudPredictionResponse(0.045, "v1.0.0"));

        // When publishing Kafka transaction event
        sendKafkaEvent(tx.getId(), tx.getAccountId(), tx.getAmount(), tx.getCurrency());

        // Then transaction reaches terminal APPROVED status in DB
        Transaction updated = awaitFinalStatus(tx.getId(), "APPROVED", Duration.ofSeconds(5));
        assertNotNull(updated, "Transaction must transition to APPROVED");
        assertEquals("APPROVED", updated.getStatus());
        assertTrue(updated.getFraudReason().contains("VERIFIED_CLEAN"),
                "Fraud reason must confirm clean ML score: " + updated.getFraudReason());
        verify(mlClient, times(1)).scoreTransaction(any(FraudPredictionRequest.class));
    }

    @Test
    @DisplayName("Scenario 2: High-value USD transaction triggers the appropriate rule (FRAUD_DETECTED)")
    void highValueUsdTransactionShouldTriggerFraudRule() throws Exception {
        // Given a transaction exceeding $10,000 USD limit
        Transaction tx = repository.save(new Transaction(
                "ACC-HIGH-001",
                BigDecimal.valueOf(15000.00),
                "USD",
                "PENDING",
                LocalDateTime.now()
        ));

        // When publishing Kafka transaction event
        sendKafkaEvent(tx.getId(), tx.getAccountId(), tx.getAmount(), tx.getCurrency());

        // Then transaction reaches FRAUD_DETECTED status in DB without invoking ML
        Transaction updated = awaitFinalStatus(tx.getId(), "FRAUD_DETECTED", Duration.ofSeconds(5));
        assertNotNull(updated, "Transaction must transition to FRAUD_DETECTED");
        assertEquals("FRAUD_DETECTED", updated.getStatus());
        assertTrue(updated.getFraudReason().contains("HIGH_VALUE_THRESHOLD_EXCEEDED"),
                "Fraud reason must indicate high-value threshold exceeded: " + updated.getFraudReason());
        verifyNoInteractions(mlClient);
    }

    @Test
    @DisplayName("Scenario 3: Unsupported currency routes to MANUAL_REVIEW")
    void unsupportedCurrencyShouldRouteToManualReview() throws Exception {
        // Given an unsupported currency (e.g. EUR)
        Transaction tx = repository.save(new Transaction(
                "ACC-CURR-001",
                BigDecimal.valueOf(250.00),
                "EUR",
                "PENDING",
                LocalDateTime.now()
        ));

        // When publishing Kafka transaction event
        sendKafkaEvent(tx.getId(), tx.getAccountId(), tx.getAmount(), tx.getCurrency());

        // Then transaction routes to MANUAL_REVIEW
        Transaction updated = awaitFinalStatus(tx.getId(), "MANUAL_REVIEW", Duration.ofSeconds(5));
        assertNotNull(updated, "Transaction must transition to MANUAL_REVIEW");
        assertEquals("MANUAL_REVIEW", updated.getStatus());
        assertTrue(updated.getFraudReason().contains("UNSUPPORTED_CURRENCY_REQUIRES_MANUAL_REVIEW"),
                "Fraud reason must state unsupported currency: " + updated.getFraudReason());
        verifyNoInteractions(mlClient);
    }

    @Test
    @DisplayName("Scenario 4: ML service unavailable routes to MANUAL_REVIEW (Fail-closed)")
    void mlServiceUnavailableShouldRouteToManualReview() throws Exception {
        // Given a standard USD transaction
        Transaction tx = repository.save(new Transaction(
                "ACC-MLFAIL-001",
                BigDecimal.valueOf(300.00),
                "USD",
                "PENDING",
                LocalDateTime.now()
        ));

        // Stub ML service failure (timeout / unreachable)
        when(mlClient.scoreTransaction(any(FraudPredictionRequest.class)))
                .thenThrow(new MlScoringException(
                        MlScoringException.ErrorCategory.TIMEOUT,
                        "ML service request timed out after 1000ms"
                ));

        // When publishing Kafka transaction event
        sendKafkaEvent(tx.getId(), tx.getAccountId(), tx.getAmount(), tx.getCurrency());

        // Then fail-closed policy routes to MANUAL_REVIEW (never auto-approved)
        Transaction updated = awaitFinalStatus(tx.getId(), "MANUAL_REVIEW", Duration.ofSeconds(5));
        assertNotNull(updated, "Transaction must transition to MANUAL_REVIEW on ML failure");
        assertEquals("MANUAL_REVIEW", updated.getStatus());
        assertTrue(updated.getFraudReason().contains("ML_SERVICE_UNAVAILABLE"),
                "Fraud reason must record ML service unavailability: " + updated.getFraudReason());
    }

    @Test
    @DisplayName("Scenario 5: Duplicate Kafka event does not produce duplicate evaluation (Idempotency)")
    void duplicateKafkaEventShouldBeSafelyIgnored() throws Exception {
        // Given an initial PENDING transaction
        Transaction tx = repository.save(new Transaction(
                "ACC-DUP-001",
                BigDecimal.valueOf(100.00),
                "USD",
                "PENDING",
                LocalDateTime.now()
        ));

        when(mlClient.scoreTransaction(any(FraudPredictionRequest.class)))
                .thenReturn(new FraudPredictionResponse(0.02, "v1.0.0"));

        // When publishing initial Kafka event
        sendKafkaEvent(tx.getId(), tx.getAccountId(), tx.getAmount(), tx.getCurrency());
        Transaction firstResult = awaitFinalStatus(tx.getId(), "APPROVED", Duration.ofSeconds(5));
        assertNotNull(firstResult);
        assertEquals("APPROVED", firstResult.getStatus());
        verify(mlClient, times(1)).scoreTransaction(any());

        // Reset mock invocation count to strictly measure second event behavior
        clearInvocations(mlClient);

        // When publishing duplicate Kafka event with identical transaction ID
        sendKafkaEvent(tx.getId(), tx.getAccountId(), tx.getAmount(), tx.getCurrency());

        // Give the consumer sufficient time to process and discard duplicate
        Thread.sleep(1000);

        // Then ML scoring was NOT invoked a second time, and status remains APPROVED
        verifyNoInteractions(mlClient);
        Transaction currentDbRecord = repository.findById(tx.getId()).orElseThrow();
        assertEquals("APPROVED", currentDbRecord.getStatus());
    }

    @Test
    @DisplayName("Scenario 6: Missing transaction is retried and routed to DLT on exhaustion")
    void missingTransactionShouldBeRetriedAndRoutedToDlt() throws Exception {
        // Transaction ID that does NOT exist in the database (simulating commit lag or missing row)
        Long nonExistentId = 999999L;

        // When publishing Kafka event for non-existent transaction
        sendKafkaEvent(nonExistentId, "ACC-MISSING", BigDecimal.valueOf(100.00), "USD");

        // Then consumer retries bounded attempts and finally routes to DLT
        ConsumerRecord<String, String> dltRecord = awaitDltRecord("999999", Duration.ofSeconds(10));

        assertNotNull(dltRecord, "Exhausted retryable event must arrive on DLT topic");
        assertEquals("ACC-MISSING", dltRecord.key());
        assertTrue(dltRecord.value().contains("999999"), "DLT record must contain transaction ID 999999");
    }

    @Test
    @DisplayName("Scenario 7: Non-retryable event reaches the dead-letter topic immediately")
    void nonRetryableEventShouldReachDltImmediately() throws Exception {
        // Malformed Kafka event missing required 'id' attribute
        Map<String, Object> malformedPayload = new HashMap<>();
        malformedPayload.put("accountId", "ACC-MALFORMED");
        malformedPayload.put("amount", 200.00);
        malformedPayload.put("currency", "USD");

        // When publishing malformed event to transaction-events
        kafkaTemplate.send(TOPIC, "ACC-MALFORMED", malformedPayload);

        // Then non-retryable event routes immediately to DLT topic
        ConsumerRecord<String, String> dltRecord = awaitDltRecord("ACC-MALFORMED", Duration.ofSeconds(10));

        assertNotNull(dltRecord, "Malformed event must arrive on DLT topic");
        assertEquals("ACC-MALFORMED", dltRecord.key());
        assertTrue(dltRecord.value().contains("ACC-MALFORMED"), "DLT payload must preserve event content");
    }

    @Test
    @DisplayName("Scenario 8: Final transaction status and fraud reason are persisted correctly")
    void finalStatusAndFraudReasonArePersistedCorrectly() throws Exception {
        // Given a high-risk transaction that ML model flags as fraud (score 0.35 >= threshold 0.14)
        Transaction tx = repository.save(new Transaction(
                "ACC-MLHIGH-001",
                BigDecimal.valueOf(800.00),
                "USD",
                "PENDING",
                LocalDateTime.now()
        ));

        when(mlClient.scoreTransaction(any(FraudPredictionRequest.class)))
                .thenReturn(new FraudPredictionResponse(0.35, "v1.0.0"));

        sendKafkaEvent(tx.getId(), tx.getAccountId(), tx.getAmount(), tx.getCurrency());

        // Then status must be FRAUD_DETECTED and reason must contain ML_HIGH_RISK_DETECTED
        Transaction updated = awaitFinalStatus(tx.getId(), "FRAUD_DETECTED", Duration.ofSeconds(5));
        assertNotNull(updated);
        assertEquals("FRAUD_DETECTED", updated.getStatus());
        assertNotNull(updated.getFraudReason());
        assertTrue(updated.getFraudReason().contains("ML_HIGH_RISK_DETECTED"),
                "Fraud reason must reflect ML detection: " + updated.getFraudReason());

        // Durably verify by re-reading directly from database
        Transaction reloaded = repository.findById(tx.getId()).orElseThrow();
        assertEquals("FRAUD_DETECTED", reloaded.getStatus());
        assertEquals(updated.getFraudReason(), reloaded.getFraudReason());
    }

    // ---------------------------------------------------------------------------------------------
    // Helper Methods
    // ---------------------------------------------------------------------------------------------

    private void sendKafkaEvent(Long id, String accountId, BigDecimal amount, String currency) {
        Map<String, Object> event = new HashMap<>();
        event.put("id", id);
        event.put("accountId", accountId);
        event.put("amount", amount);
        event.put("currency", currency);
        event.put("timestamp", LocalDateTime.now().toString());

        kafkaTemplate.send(TOPIC, accountId, event);
    }

    private Transaction awaitFinalStatus(Long id, String expectedStatus, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<Transaction> txOpt = repository.findById(id);
            if (txOpt.isPresent()) {
                Transaction tx = txOpt.get();
                if (expectedStatus.equalsIgnoreCase(tx.getStatus())) {
                    return tx;
                }
            }
            Thread.sleep(100);
        }
        return repository.findById(id).orElse(null);
    }

    private ConsumerRecord<String, String> awaitDltRecord(String expectedKeyOrValueSnippet, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = dltConsumer.poll(Duration.ofMillis(200));
            for (ConsumerRecord<String, String> record : records) {
                if ((record.key() != null && record.key().contains(expectedKeyOrValueSnippet)) ||
                        (record.value() != null && record.value().contains(expectedKeyOrValueSnippet))) {
                    return record;
                }
            }
        }
        return null;
    }
}
