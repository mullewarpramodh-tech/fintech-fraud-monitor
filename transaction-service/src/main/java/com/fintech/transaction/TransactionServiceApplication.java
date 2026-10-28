package com.fintech.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TransactionServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(TransactionServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }

    @Bean
    public CommandLineRunner startupBanner() {
        return args -> {
            log.info("\n" +
                "=================================================================================\n" +
                "  🚀 DISTRIBUTED FINTECH FRAUD MONITOR - LIVE WEB CONSOLE\n" +
                "  👉 Interactive Dashboard: http://localhost:8081\n" +
                "  👉 Embedded Kafka Broker: localhost:9092 [Topic: transaction-events]\n" +
                "  👉 Cloud Persistence:     Neon Serverless PostgreSQL\n" +
                "=================================================================================");
        };
    }
}
