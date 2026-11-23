package com.fintech.fraud.service;

/**
 * Result of evaluating a transaction through the multi-tier fraud decision engine.
 */
public record FraudDecisionResult(
    String status,
    String fraudReason,
    Double mlProbability,
    String mlModelVersion
) {}
