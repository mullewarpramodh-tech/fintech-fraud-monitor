package com.fintech.fraud.service;

import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.model.Transaction;

import java.math.BigDecimal;

/**
 * Interface for extracting engineered runtime features from a persisted transaction.
 */
public interface TransactionFeatureExtractor {

    BigDecimal COLD_START_BASELINE = new BigDecimal("100.00");
    BigDecimal RATIO_EPSILON = new BigDecimal("0.00001");
    String SUPPORTED_CURRENCY = "USD";

    /**
     * Verifies whether the transaction currency is supported by the ML inference model.
     */
    boolean isSupportedCurrency(String currency);

    /**
     * Extracts all 7 features matching the Python training and preprocessing pipeline.
     */
    FraudPredictionRequest extractFeatures(Transaction transaction);
}
