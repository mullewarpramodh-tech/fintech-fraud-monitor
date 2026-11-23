package com.fintech.fraud.service;

import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.model.Transaction;
import com.fintech.fraud.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Transaction Feature Extractor — Unit Tests")
class TransactionFeatureExtractorTest {

    private TransactionRepository repository;
    private TransactionFeatureExtractor extractor;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TransactionRepository.class);
        extractor = new DefaultTransactionFeatureExtractor(repository);
    }

    @Test
    @DisplayName("Cold Start: Applies $100.00 baseline when account has no 24h history")
    void shouldApplyColdStartBaselineWhenNoHistory() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        Transaction tx = new Transaction("ACC-NEW", BigDecimal.valueOf(150.00), "USD", "PENDING", timestamp);
        tx.setId(501L);

        when(repository.countPriorTransactions(eq("ACC-NEW"), any(), eq(timestamp), eq(501L))).thenReturn(0L);
        when(repository.getAveragePriorAmount(eq("ACC-NEW"), any(), eq(timestamp), eq(501L))).thenReturn(null);

        FraudPredictionRequest request = extractor.extractFeatures(tx);

        assertNotNull(request);
        assertEquals(BigDecimal.valueOf(150.00), request.amount());
        assertEquals(0, request.velocity60s());
        assertEquals(0, request.txCountLast1h());
        assertEquals(new BigDecimal("100.00"), request.avgAmountLast24h());

        // Ratio: 150.00 / (100.00 + 0.00001) ~ 1.499999
        assertEquals(1.499999, request.amountToAvgRatio(), 0.0001);
    }

    @Test
    @DisplayName("Populated History: Correctly extracts counts, 24h average and ratio")
    void shouldExtractFeaturesWithPopulatedHistory() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 1, 15, 18, 30, 0);
        Transaction tx = new Transaction("ACC-ACTIVE", BigDecimal.valueOf(300.00), "USD", "PENDING", timestamp);
        tx.setId(502L);

        // 2 in 60s, 4 in 1h, prior average $150.00
        when(repository.countPriorTransactions(eq("ACC-ACTIVE"), eq(timestamp.minusSeconds(60)), eq(timestamp), eq(502L))).thenReturn(2L);
        when(repository.countPriorTransactions(eq("ACC-ACTIVE"), eq(timestamp.minusHours(1)), eq(timestamp), eq(502L))).thenReturn(4L);
        when(repository.getAveragePriorAmount(eq("ACC-ACTIVE"), eq(timestamp.minusHours(24)), eq(timestamp), eq(502L))).thenReturn(150.00);

        FraudPredictionRequest request = extractor.extractFeatures(tx);

        assertEquals(2, request.velocity60s());
        assertEquals(4, request.txCountLast1h());
        assertEquals(new BigDecimal("150.00"), request.avgAmountLast24h());
        // Ratio: 300.00 / (150.00 + 0.00001) ~ 1.999999
        assertEquals(1.999999, request.amountToAvgRatio(), 0.0001);
    }

    @Test
    @DisplayName("Cyclic Hour Encoding: Verifies exact sine and cosine calculations")
    void shouldEncodeCyclicHourAccurately() {
        // Test at midnight 00:00:00 -> sin(0) = 0, cos(0) = 1
        LocalDateTime midnight = LocalDateTime.of(2026, 1, 15, 0, 0, 0);
        Transaction txMidnight = new Transaction("ACC-TIME", BigDecimal.valueOf(100.00), "USD", "PENDING", midnight);
        txMidnight.setId(503L);

        FraudPredictionRequest reqMidnight = extractor.extractFeatures(txMidnight);
        assertEquals(0.0, reqMidnight.sinHour(), 0.0001);
        assertEquals(1.0, reqMidnight.cosHour(), 0.0001);

        // Test at 06:00:00 -> sin(pi/2) = 1, cos(pi/2) = 0
        LocalDateTime morning = LocalDateTime.of(2026, 1, 15, 6, 0, 0);
        Transaction txMorning = new Transaction("ACC-TIME", BigDecimal.valueOf(100.00), "USD", "PENDING", morning);
        txMorning.setId(504L);

        FraudPredictionRequest reqMorning = extractor.extractFeatures(txMorning);
        assertEquals(1.0, reqMorning.sinHour(), 0.0001);
        assertEquals(0.0, reqMorning.cosHour(), 0.0001);

        // Test at 12:00:00 -> sin(pi) = 0, cos(pi) = -1
        LocalDateTime noon = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        Transaction txNoon = new Transaction("ACC-TIME", BigDecimal.valueOf(100.00), "USD", "PENDING", noon);
        txNoon.setId(505L);

        FraudPredictionRequest reqNoon = extractor.extractFeatures(txNoon);
        assertEquals(0.0, reqNoon.sinHour(), 0.0001);
        assertEquals(-1.0, reqNoon.cosHour(), 0.0001);
    }

    @Test
    @DisplayName("Currency Check: Validates USD as supported and others as unsupported")
    void shouldValidateSupportedCurrencies() {
        assertTrue(extractor.isSupportedCurrency("USD"));
        assertTrue(extractor.isSupportedCurrency("usd"));
        assertFalse(extractor.isSupportedCurrency("INR"));
        assertFalse(extractor.isSupportedCurrency("EUR"));
        assertFalse(extractor.isSupportedCurrency(null));
    }
}
