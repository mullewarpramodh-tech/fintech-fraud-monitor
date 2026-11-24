package com.fintech.fraud.repository;

import com.fintech.fraud.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Preserved for backward compatibility with initial baseline rule checks.
     */
    long countByAccountIdAndTimestampAfter(String accountId, LocalDateTime windowStart);

    /**
     * Atomically transitions a transaction status from PENDING to EVALUATING.
     * Guarantees that only one concurrent consumer or Kafka duplicate redelivery can claim the event.
     * Returns 1 if successfully claimed, 0 if already claimed or finalized.
     * clearAutomatically = true ensures Hibernate first-level cache does not return stale entity state.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Transaction t SET t.status = 'EVALUATING' WHERE t.id = :id AND t.status = 'PENDING'")
    int claimTransactionForEvaluation(@Param("id") Long id);

    /**
     * Crash recovery: Resets orphaned transactions stuck in EVALUATING after service failure/restart
     * back to PENDING so they can be re-evaluated.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Transaction t SET t.status = 'PENDING' WHERE t.status = 'EVALUATING' AND t.timestamp < :staleThreshold")
    int resetStaleEvaluatingTransactions(@Param("staleThreshold") LocalDateTime staleThreshold);

    /**
     * Counts strictly prior transactions for an account in the window [windowStart, currentTimestamp),
     * strictly excluding the current transaction ID to prevent self-counting and future leakage.
     */
    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.timestamp >= :windowStart " +
           "AND t.timestamp < :currentTimestamp " +
           "AND t.id != :currentId")
    long countPriorTransactions(
            @Param("accountId") String accountId,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("currentTimestamp") LocalDateTime currentTimestamp,
            @Param("currentId") Long currentId);

    /**
     * Computes average amount of strictly prior transactions for an account in [windowStart, currentTimestamp),
     * strictly excluding the current transaction ID.
     * Returns null if no prior transactions exist within the window (handled via cold-start fallback).
     */
    @Query("SELECT AVG(t.amount) FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.timestamp >= :windowStart " +
           "AND t.timestamp < :currentTimestamp " +
           "AND t.id != :currentId")
    Double getAveragePriorAmount(
            @Param("accountId") String accountId,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("currentTimestamp") LocalDateTime currentTimestamp,
            @Param("currentId") Long currentId);
}
