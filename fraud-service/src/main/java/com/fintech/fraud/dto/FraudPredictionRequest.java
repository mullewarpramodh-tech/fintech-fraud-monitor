package com.fintech.fraud.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * DTO representing the 7 engineered features expected by the Python ML inference service.
 * Definitions are strictly aligned with training and preprocessing pipeline.
 */
public record FraudPredictionRequest(
    @JsonProperty("amount") BigDecimal amount,
    @JsonProperty("tx_count_last_1h") int txCountLast1h,
    @JsonProperty("avg_amount_last_24h") BigDecimal avgAmountLast24h,
    @JsonProperty("amount_to_avg_ratio") double amountToAvgRatio,
    @JsonProperty("velocity_60s") int velocity60s,
    @JsonProperty("sin_hour") double sinHour,
    @JsonProperty("cos_hour") double cosHour
) {}
