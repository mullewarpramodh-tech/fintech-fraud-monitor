package com.fintech.fraud.service;

import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.model.Transaction;
import com.fintech.fraud.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Default implementation of TransactionFeatureExtractor.
 * Extracts the 7 engineered features using strict [windowStart, currentTimestamp) boundaries,
 * excluding the current transaction ID to prevent self-counting and future leakage.
 *
 * Feature Parity & Precision:
 * - Timestamps: Assumes UTC standardization. Hour float is computed in [0.0, 24.0) UTC.
 * - Cold-start: Defaults to $100.00 baseline when account has no transactions in the last 24h.
 * - Ratio: amount / (avg_amount_last_24h + 1e-5) using MathContext.DECIMAL64 (16 digits precision)
 *   to match 64-bit IEEE 754 floating point arithmetic in Python without premature rounding.
 */
@Service
public class DefaultTransactionFeatureExtractor implements TransactionFeatureExtractor {

    private static final Logger log = LoggerFactory.getLogger(DefaultTransactionFeatureExtractor.class);
    private final TransactionRepository repository;

    public DefaultTransactionFeatureExtractor(TransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isSupportedCurrency(String currency) {
        return SUPPORTED_CURRENCY.equalsIgnoreCase(currency);
    }

    @Override
    public FraudPredictionRequest extractFeatures(Transaction transaction) {
        if (transaction == null || transaction.getTimestamp() == null) {
            throw new IllegalArgumentException("Transaction and persisted timestamp must not be null");
        }

        String accountId = transaction.getAccountId();
        Long currentId = transaction.getId();
        LocalDateTime currentTimestamp = transaction.getTimestamp();
        BigDecimal amount = transaction.getAmount();

        // 1. Sliding window count for last 60 seconds: [t - 60s, t)
        LocalDateTime window60s = currentTimestamp.minusSeconds(60);
        long velocity60sRaw = repository.countPriorTransactions(accountId, window60s, currentTimestamp, currentId);
        int velocity60s = (int) Math.min(velocity60sRaw, Integer.MAX_VALUE);

        // 2. Sliding window count for last 1 hour: [t - 1h, t)
        LocalDateTime window1h = currentTimestamp.minusHours(1);
        long txCountLast1hRaw = repository.countPriorTransactions(accountId, window1h, currentTimestamp, currentId);
        int txCountLast1h = (int) Math.min(txCountLast1hRaw, Integer.MAX_VALUE);

        // 3. Historical average amount for last 24 hours: [t - 24h, t)
        LocalDateTime window24h = currentTimestamp.minusHours(24);
        Double avg24hResult = repository.getAveragePriorAmount(accountId, window24h, currentTimestamp, currentId);

        BigDecimal avgAmountLast24h;
        if (avg24hResult == null) {
            // Cold-start fallback: Account has no prior history in the last 24 hours
            avgAmountLast24h = COLD_START_BASELINE;
            log.debug("Cold-start baseline ($100.00) applied for account '{}'", accountId);
        } else {
            avgAmountLast24h = BigDecimal.valueOf(avg24hResult).setScale(2, RoundingMode.HALF_UP);
        }

        // 4. Exact ratio matching training pipeline: amount / (avg_amount_last_24h + 1e-5)
        // Uses MathContext.DECIMAL64 (16 digits precision) matching 64-bit IEEE 754 division in Python
        BigDecimal denominator = avgAmountLast24h.add(RATIO_EPSILON);
        double amountToAvgRatio = amount.divide(denominator, MathContext.DECIMAL64).doubleValue();

        // 5. Cyclic hour of day encoding from UTC timestamp: sin_hour, cos_hour
        double hourFloat = currentTimestamp.getHour()
                + currentTimestamp.getMinute() / 60.0
                + currentTimestamp.getSecond() / 3600.0;
        double sinHour = Math.sin(2.0 * Math.PI * hourFloat / 24.0);
        double cosHour = Math.cos(2.0 * Math.PI * hourFloat / 24.0);

        return new FraudPredictionRequest(
                amount,
                txCountLast1h,
                avgAmountLast24h,
                amountToAvgRatio,
                velocity60s,
                sinHour,
                cosHour
        );
    }
}
