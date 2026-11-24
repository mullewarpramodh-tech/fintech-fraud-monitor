package com.fintech.fraud.config;

import com.fintech.fraud.exception.RetryableTransactionException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka Consumer Reliability & Error Handling Configuration.
 * Configures bounded retries with backoff and dead-letter topic (DLT) routing.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    public static final String DEFAULT_DLT_TOPIC = "transaction-events-dlt";

    private final String dltTopic;
    private final long retryIntervalMs;
    private final long maxRetryAttempts;

    public KafkaConsumerConfig(
            @Value("${fraud.kafka.dlt-topic:transaction-events-dlt}") String dltTopic,
            @Value("${fraud.kafka.retry-interval-ms:1000}") long retryIntervalMs,
            @Value("${fraud.kafka.max-retry-attempts:2}") long maxRetryAttempts) {
        this.dltTopic = dltTopic;
        this.retryIntervalMs = retryIntervalMs;
        this.maxRetryAttempts = maxRetryAttempts;
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler(@Autowired(required = false) KafkaOperations<Object, Object> kafkaOperations) {
        ConsumerRecordRecoverer recoverer;

        if (kafkaOperations != null) {
            recoverer = new DeadLetterPublishingRecoverer(kafkaOperations, (record, ex) -> {
                log.error("Routing failed Kafka record [topic={}, partition={}, offset={}] to DLT '{}'. Cause: {}",
                        record.topic(), record.partition(), record.offset(), dltTopic, ex.getMessage());
                return new TopicPartition(dltTopic, record.partition());
            });
        } else {
            recoverer = (record, ex) -> log.error(
                    "Retries exhausted for record [topic={}, partition={}, offset={}]. DLT KafkaOperations not available. Cause: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage());
        }

        // Bounded retries with backoff: initial attempt + maxRetryAttempts (default 2 retries = 3 total attempts)
        FixedBackOff backOff = new FixedBackOff(retryIntervalMs, maxRetryAttempts);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Retryable: Out-of-order DB commit lag and transient database lock/connection errors
        errorHandler.addRetryableExceptions(
                RetryableTransactionException.class,
                DataAccessException.class
        );

        // Not retryable: Poison pills and malformed events route to DLT immediately without wasting retries
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class
        );

        return errorHandler;
    }

    @Bean
    public NewTopic transactionEventsDltTopic() {
        return TopicBuilder.name(dltTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
