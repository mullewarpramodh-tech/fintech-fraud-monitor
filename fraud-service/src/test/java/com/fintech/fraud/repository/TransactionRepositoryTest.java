package com.fintech.fraud.repository;

import com.fintech.fraud.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@DisplayName("Transaction Repository — Historical Queries, Boundaries & Idempotency Tests")
class TransactionRepositoryTest {

    @Autowired
    private TransactionRepository repository;

    @Test
    @DisplayName("countPriorTransactions: strictly enforces windowStart <= t < currentTimestamp and excludes currentId")
    void shouldCountStrictlyPriorTransactionsExcludingCurrentId() {
        String accountId = "ACC-TEST-001";
        LocalDateTime now = LocalDateTime.of(2026, 1, 15, 12, 0, 0);

        // 1. Transaction 45 minutes prior (inside 1h window)
        Transaction tPrior1 = new Transaction(accountId, BigDecimal.valueOf(50.00), "USD", "APPROVED", now.minusMinutes(45));
        repository.save(tPrior1);

        // 2. Transaction 10 minutes prior (inside 1h window)
        Transaction tPrior2 = new Transaction(accountId, BigDecimal.valueOf(75.00), "USD", "APPROVED", now.minusMinutes(10));
        repository.save(tPrior2);

        // 3. Transaction 90 minutes prior (outside 1h window)
        Transaction tOld = new Transaction(accountId, BigDecimal.valueOf(200.00), "USD", "APPROVED", now.minusMinutes(90));
        repository.save(tOld);

        // 4. Current transaction at 'now'
        Transaction tCurrent = new Transaction(accountId, BigDecimal.valueOf(120.00), "USD", "PENDING", now);
        tCurrent = repository.save(tCurrent);

        // 5. Future transaction after 'now'
        Transaction tFuture = new Transaction(accountId, BigDecimal.valueOf(300.00), "USD", "PENDING", now.plusMinutes(5));
        repository.save(tFuture);

        // 6. Another account's transaction in the same window
        Transaction tOther = new Transaction("ACC-OTHER", BigDecimal.valueOf(500.00), "USD", "APPROVED", now.minusMinutes(20));
        repository.save(tOther);

        LocalDateTime window1h = now.minusHours(1);

        // Execute query
        long count = repository.countPriorTransactions(accountId, window1h, now, tCurrent.getId());

        // Expected: exactly 2 transactions (tPrior1 and tPrior2)
        assertEquals(2, count, "Should only count strictly prior transactions for the account within the window");
    }

    @Test
    @DisplayName("getAveragePriorAmount: accurately averages prior amounts and returns null on cold start")
    void shouldComputeAveragePriorAmountAndHandleColdStart() {
        String accountId = "ACC-TEST-AVG";
        LocalDateTime now = LocalDateTime.of(2026, 1, 15, 14, 0, 0);
        LocalDateTime window24h = now.minusHours(24);

        // Current transaction with no prior history (cold start)
        Transaction currentTx = repository.save(new Transaction(accountId, BigDecimal.valueOf(150.00), "USD", "PENDING", now));

        Double coldStartAvg = repository.getAveragePriorAmount(accountId, window24h, now, currentTx.getId());
        assertNull(coldStartAvg, "Cold start should return null when no prior transactions exist in 24h");

        // Now insert prior transactions: $40.00 and $60.00 (expected avg = $50.00)
        repository.save(new Transaction(accountId, BigDecimal.valueOf(40.00), "USD", "APPROVED", now.minusHours(5)));
        repository.save(new Transaction(accountId, BigDecimal.valueOf(60.00), "USD", "APPROVED", now.minusHours(2)));

        Double computedAvg = repository.getAveragePriorAmount(accountId, window24h, now, currentTx.getId());
        assertNotNull(computedAvg);
        assertEquals(50.00, computedAvg, 0.001, "Average should exclude current transaction and compute correct mean");
    }

    @Test
    @DisplayName("claimTransactionForEvaluation: atomic claim returns 1 once and 0 for duplicates")
    void shouldAtomicallyClaimPendingTransactionOnce() {
        Transaction tx = repository.save(new Transaction("ACC-CLAIM", BigDecimal.valueOf(100.00), "USD", "PENDING", LocalDateTime.now()));
        Long txId = tx.getId();

        // First attempt claims it
        int rowsUpdated1 = repository.claimTransactionForEvaluation(txId);
        assertEquals(1, rowsUpdated1, "First claim attempt must succeed with 1 row updated");

        // Verify status in DB is now EVALUATING
        Transaction updated = repository.findById(txId).orElseThrow();
        assertEquals("EVALUATING", updated.getStatus());

        // Duplicate concurrent/redelivery attempt fails
        int rowsUpdated2 = repository.claimTransactionForEvaluation(txId);
        assertEquals(0, rowsUpdated2, "Subsequent attempt on claimed transaction must return 0 rows updated");
    }

    @Test
    @DisplayName("resetStaleEvaluatingTransactions: resets orphaned EVALUATING transactions back to PENDING")
    void shouldResetStaleEvaluatingTransactions() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleTime = now.minusMinutes(15);

        // 1. Stale transaction stuck in EVALUATING for 15 minutes (e.g. crash)
        Transaction staleTx = repository.save(new Transaction("ACC-STALE", BigDecimal.valueOf(99.00), "USD", "EVALUATING", staleTime));

        // 2. Fresh transaction in EVALUATING (e.g. active worker)
        Transaction freshTx = repository.save(new Transaction("ACC-FRESH", BigDecimal.valueOf(50.00), "USD", "EVALUATING", now.minusSeconds(10)));

        // Threshold: 5 minutes ago
        LocalDateTime threshold = now.minusMinutes(5);
        int resetCount = repository.resetStaleEvaluatingTransactions(threshold);

        assertEquals(1, resetCount, "Should only reset stale transaction");
        assertEquals("PENDING", repository.findById(staleTx.getId()).orElseThrow().getStatus());
        assertEquals("EVALUATING", repository.findById(freshTx.getId()).orElseThrow().getStatus());
    }
}
