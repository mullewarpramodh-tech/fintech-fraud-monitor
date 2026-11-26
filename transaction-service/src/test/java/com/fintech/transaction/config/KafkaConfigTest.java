package com.fintech.transaction.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Transaction Service - Kafka Configuration Unit Tests")
class KafkaConfigTest {

    @Test
    @DisplayName("Should declare transaction-events NewTopic bean with 1 partition and 1 replica")
    void shouldDeclareTransactionEventsTopic() {
        KafkaConfig config = new KafkaConfig();
        NewTopic topic = config.transactionEventsTopic();

        assertNotNull(topic);
        assertEquals("transaction-events", topic.name());
        assertEquals(1, topic.numPartitions());
        assertEquals((short) 1, topic.replicationFactor());
    }

    @Test
    @DisplayName("Should verify KafkaConfig contains no EmbeddedKafkaBroker methods or beans")
    void shouldNotContainEmbeddedBrokerBean() {
        Method[] methods = KafkaConfig.class.getDeclaredMethods();
        boolean hasEmbeddedBroker = Arrays.stream(methods)
                .anyMatch(m -> m.getName().toLowerCase().contains("embedded")
                        || m.getReturnType().getSimpleName().contains("EmbeddedKafkaBroker"));

        assertFalse(hasEmbeddedBroker, "KafkaConfig must not declare an EmbeddedKafkaBroker in production code");
    }
}
