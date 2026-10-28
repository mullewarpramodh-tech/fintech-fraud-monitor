package com.fintech.fraud.service;

import com.fintech.fraud.model.Transaction;
import com.fintech.fraud.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Fraud Detection Service - Multi-Tier Rule Engine Unit Tests")
class FraudDetectionConsumerTest {

    private TransactionRepository repository;
    private FraudDetectionConsumer consumer;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TransactionRepository.class);
        consumer = new FraudDetectionConsumer(repository);
    }

    @Test
    @DisplayName("Rule 1: Should flag transactions exceeding $10,000 threshold as FRAUD_DETECTED")
    void shouldFlagHighValueTransactions() {
        Transaction tx = new Transaction();
        tx.setId(101L);
        tx.setAccountId("ACC-9999");
        tx.setAmount(BigDecimal.valueOf(15000.0));
        tx.setStatus("PENDING");
        tx.setTimestamp(LocalDateTime.now());

        when(repository.findById(101L)).thenReturn(Optional.of(tx));
        when(repository.countByAccountIdAndTimestampAfter(eq("ACC-9999"), any(LocalDateTime.class))).thenReturn(1L);

        Map<String, Object> event = new HashMap<>();
        event.put("id", 101L);
        event.put("accountId", "ACC-9999");
        event.put("amount", 15000.0);

        consumer.consumeTransactionEvent(event);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(1)).save(captor.capture());

        Transaction savedTx = captor.getValue();
        assertEquals("FRAUD_DETECTED", savedTx.getStatus());
        assertTrue(savedTx.getFraudReason().contains("HIGH_VALUE_THRESHOLD_EXCEEDED"));
    }

    @Test
    @DisplayName("Rule 2: Should flag rapid burst (>3 transactions in 60s) as FRAUD_DETECTED velocity spike")
    void shouldFlagVelocitySpikeTransactions() {
        Transaction tx = new Transaction();
        tx.setId(102L);
        tx.setAccountId("ACC-BURST");
        tx.setAmount(BigDecimal.valueOf(250.0));
        tx.setStatus("PENDING");
        tx.setTimestamp(LocalDateTime.now());

        when(repository.findById(102L)).thenReturn(Optional.of(tx));
        when(repository.countByAccountIdAndTimestampAfter(eq("ACC-BURST"), any(LocalDateTime.class))).thenReturn(5L);

        Map<String, Object> event = new HashMap<>();
        event.put("id", 102L);
        event.put("accountId", "ACC-BURST");
        event.put("amount", 250.0);

        consumer.consumeTransactionEvent(event);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(1)).save(captor.capture());

        Transaction savedTx = captor.getValue();
        assertEquals("FRAUD_DETECTED", savedTx.getStatus());
        assertTrue(savedTx.getFraudReason().contains("VELOCITY_SPIKE_EXCEEDED"));
    }

    @Test
    @DisplayName("Rule 3: Should approve clean normal transactions within limit and low velocity")
    void shouldApproveCleanTransaction() {
        Transaction tx = new Transaction();
        tx.setId(103L);
        tx.setAccountId("ACC-CLEAN");
        tx.setAmount(BigDecimal.valueOf(120.0));
        tx.setStatus("PENDING");
        tx.setTimestamp(LocalDateTime.now());

        when(repository.findById(103L)).thenReturn(Optional.of(tx));
        when(repository.countByAccountIdAndTimestampAfter(eq("ACC-CLEAN"), any(LocalDateTime.class))).thenReturn(1L);

        Map<String, Object> event = new HashMap<>();
        event.put("id", 103L);
        event.put("accountId", "ACC-CLEAN");
        event.put("amount", 120.0);

        consumer.consumeTransactionEvent(event);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(1)).save(captor.capture());

        Transaction savedTx = captor.getValue();
        assertEquals("APPROVED", savedTx.getStatus());
        assertEquals("VERIFIED_CLEAN", savedTx.getFraudReason());
    }
}
