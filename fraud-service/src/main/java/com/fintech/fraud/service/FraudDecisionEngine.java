package com.fintech.fraud.service;

import com.fintech.fraud.model.Transaction;

/**
 * Interface for the multi-tier Fraud Decision Engine.
 * Evaluates transactions against business rules, currency requirements, and calibrated ML inference.
 */
public interface FraudDecisionEngine {
    FraudDecisionResult evaluate(Transaction transaction);
}
