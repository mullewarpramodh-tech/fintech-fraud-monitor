package com.fintech.transaction;

import com.fintech.transaction.model.User;
import com.fintech.transaction.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
public class TransactionServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(TransactionServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }

    /**
     * Seeds initial development/local demo users with BCrypt-hashed credentials.
     * Restricted strictly to 'dev' and 'local' profiles to prevent automated
     * credential creation in production or staging environments.
     */
    @Bean
    @Profile({"dev", "local"})
    public CommandLineRunner initDefaultUsers(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (userRepository.count() == 0) {
                userRepository.save(new User("admin", passwordEncoder.encode("admin123"), "ROLE_ADMIN"));
                userRepository.save(new User("auditor", passwordEncoder.encode("auditor123"), "ROLE_AUDITOR"));
                userRepository.save(new User("client", passwordEncoder.encode("client123"), "ROLE_CLIENT", "ACC-CLIENT-101"));
                userRepository.save(new User("user", passwordEncoder.encode("user123"), "ROLE_USER", "ACC-USER-101"));
                log.info("Seeded default demo users into database with BCrypt-hashed passwords.");
            }
        };
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
