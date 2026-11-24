package com.fintech.transaction.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;

/**
 * Test-scoped Embedded Kafka configuration.
 * Used exclusively by test suites requiring an in-process Kafka broker.
 */
@TestConfiguration
public class TestKafkaConfig {

    public static final String TEST_TOPIC = "transaction-events";

    @Bean
    public EmbeddedKafkaBroker testEmbeddedKafkaBroker() {
        return new EmbeddedKafkaZKBroker(1, true, TEST_TOPIC);
    }
}
