package com.fintech.fraud.repository;

import com.fintech.fraud.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // Counts transactions for a specific account since a given timestamp (the sliding window threshold)
    long countByAccountIdAndTimestampAfter(String accountId, LocalDateTime windowStart);
}
