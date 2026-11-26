package com.fintech.transaction.controller;

import com.fintech.transaction.model.Transaction;
import com.fintech.transaction.model.User;
import com.fintech.transaction.repository.TransactionRepository;
import com.fintech.transaction.repository.UserRepository;
import com.fintech.transaction.service.TransactionProducer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionRepository repository;
    private final TransactionProducer producer;
    private final UserRepository userRepository;

    public TransactionController(TransactionRepository repository,
                                 TransactionProducer producer,
                                 UserRepository userRepository) {
        this.repository = repository;
        this.producer = producer;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<Transaction> createTransaction(@RequestBody Transaction request) {
        if (request == null || request.getAccountId() == null || request.getAccountId().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        // SEC-06 Account Ownership Enforcement:
        // Non-admin clients/users may ONLY create transactions for their authorized account
        if (!isAdmin) {
            String username = auth.getName();
            Optional<User> userOpt = userRepository.findByUsername(username);

            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            User user = userOpt.get();
            String authorizedAccount = user.getAccountId();

            if (authorizedAccount == null || !authorizedAccount.equalsIgnoreCase(request.getAccountId().trim())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        // 1. Create a new transaction object (defaults to PENDING status)
        Transaction transaction = new Transaction(request.getAccountId().trim(), request.getAmount(), request.getCurrency());
        
        // 2. Persist initial PENDING record to Neon PostgreSQL
        Transaction saved = repository.save(transaction);
        
        // 3. Asynchronously publish event to Apache Kafka for downstream fraud scoring
        producer.sendTransactionEvent(saved);
        
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<List<Transaction>> getAllTransactions() {
        // Enforced via SecurityConfig: Only accessible by ROLE_ADMIN or ROLE_AUDITOR
        return ResponseEntity.ok(repository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Transaction> getTransactionById(@PathVariable Long id) {
        return repository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
