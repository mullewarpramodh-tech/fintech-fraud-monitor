package com.fintech.fraud.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO representing the response from Python's POST /predict endpoint.
 * Contains only probability and model version; Java retains sole authority for business decisions.
 */
public record FraudPredictionResponse(
    @JsonProperty("fraud_probability") Double fraudProbability,
    @JsonProperty("model_version") String modelVersion
) {
    /**
     * Validates that the probability is non-null, finite, and strictly bounded [0.0, 1.0].
     */
    public void validate() {
        if (fraudProbability == null || !Double.isFinite(fraudProbability) || fraudProbability < 0.0 || fraudProbability > 1.0) {
            throw new IllegalArgumentException(
                "Invalid fraud_probability: must be a finite number between 0.0 and 1.0, got: " + fraudProbability
            );
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("Invalid model_version: must be a non-empty string");
        }
    }
}
