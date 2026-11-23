package com.fintech.fraud.client;

import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.dto.FraudPredictionResponse;

/**
 * Client interface for interacting with the Python ML inference service.
 */
public interface MlFraudScoringClient {

    /**
     * Sends engineered transaction features to the Python ML model and returns validated probability.
     */
    FraudPredictionResponse scoreTransaction(FraudPredictionRequest request);
}
