package com.fintech.transaction.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Configuration for Transaction Service.
 * Connects to external Kafka broker via spring.kafka.bootstrap-servers.
 * Declares the transaction-events topic.
 */
@Configuration
public class KafkaConfig {

    public static final String TOPIC = "transaction-events";

    @Bean
    public NewTopic transactionEventsTopic() {
        return TopicBuilder.name(TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
