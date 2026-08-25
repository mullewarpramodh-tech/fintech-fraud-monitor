package com.fintech.transaction.controller;

import com.fintech.transaction.model.Transaction;
import com.fintech.transaction.repository.TransactionRepository;
import com.fintech.transaction.service.TransactionProducer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        
        // 2. Save it to the Neon PostgreSQL database
        Transaction saved = repository.save(transaction);
        
        // 3. Publish it to Apache Kafka so the Fraud Service can process it
        producer.sendTransactionEvent(saved);
        
        return ResponseEntity.ok(saved);
    }
}
