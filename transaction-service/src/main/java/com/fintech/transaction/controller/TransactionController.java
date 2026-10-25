package com.fintech.transaction.controller;

import com.fintech.transaction.model.Transaction;
import com.fintech.transaction.repository.TransactionRepository;
import com.fintech.transaction.service.TransactionProducer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionRepository repository;
    private final TransactionProducer producer;

    public TransactionController(TransactionRepository repository, TransactionProducer producer) {
        this.repository = repository;
        this.producer = producer;
    }

    @PostMapping
    public ResponseEntity<Transaction> createTransaction(@RequestBody Transaction request) {
        // 1. Create a new transaction object (defaults to PENDING status)
        Transaction transaction = new Transaction(request.getAccountId(), request.getAmount(), request.getCurrency());
        
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
