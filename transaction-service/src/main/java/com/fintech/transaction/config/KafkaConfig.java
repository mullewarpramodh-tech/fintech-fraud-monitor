package com.fintech.transaction.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;

@Configuration
public class KafkaConfig {

    @Bean
    public EmbeddedKafkaBroker embeddedKafkaBroker() {
        // Spin up a mini Kafka server on port 9092 and auto-create our topic
        EmbeddedKafkaBroker broker = new EmbeddedKafkaZKBroker(1, true, "transaction-events");
        broker.kafkaPorts(9092);
        return broker;
    }
}
