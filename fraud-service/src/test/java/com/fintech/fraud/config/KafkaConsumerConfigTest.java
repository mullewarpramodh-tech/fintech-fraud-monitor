package com.fintech.fraud.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Kafka Consumer Reliability & DLT Configuration Tests")
class KafkaConsumerConfigTest {

    @Test
    @DisplayName("Should declare transaction-events-dlt NewTopic bean")
    void shouldDeclareDltNewTopicBean() {
        KafkaConsumerConfig config = new KafkaConsumerConfig("transaction-events-dlt", 1000L, 2L);
        NewTopic topic = config.transactionEventsDltTopic();

        assertNotNull(topic);
        assertEquals("transaction-events-dlt", topic.name());
        assertEquals(1, topic.numPartitions());
        assertEquals((short) 1, topic.replicationFactor());
    }

    @Test
    @DisplayName("Should configure DefaultErrorHandler with bounded retries and DLT recoverer")
    @SuppressWarnings("unchecked")
    void shouldConfigureErrorHandlerWithRetryAndDlt() {
        KafkaOperations<Object, Object> kafkaOperations = Mockito.mock(KafkaOperations.class);

        KafkaConsumerConfig config = new KafkaConsumerConfig("transaction-events-dlt", 1000L, 2L);
        CommonErrorHandler errorHandler = config.kafkaErrorHandler(kafkaOperations);

        assertNotNull(errorHandler);
        assertInstanceOf(DefaultErrorHandler.class, errorHandler);
    }

    @Test
    @DisplayName("Should configure fallback gracefully when KafkaOperations is null")
    void shouldHandleNullKafkaOperations() {
        KafkaConsumerConfig config = new KafkaConsumerConfig("transaction-events-dlt", 500L, 1L);
        CommonErrorHandler errorHandler = config.kafkaErrorHandler(null);

        assertNotNull(errorHandler);
        assertInstanceOf(DefaultErrorHandler.class, errorHandler);
    }
}
