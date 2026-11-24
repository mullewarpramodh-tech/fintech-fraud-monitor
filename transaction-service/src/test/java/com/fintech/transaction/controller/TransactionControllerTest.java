package com.fintech.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.transaction.config.SecurityConfig;
import com.fintech.transaction.model.Transaction;
import com.fintech.transaction.model.User;
import com.fintech.transaction.repository.TransactionRepository;
import com.fintech.transaction.repository.UserRepository;
import com.fintech.transaction.security.JwtAuthFilter;
import com.fintech.transaction.security.JwtUtil;
import com.fintech.transaction.service.TransactionProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class})
@DisplayName("Transaction Controller — REST API, Security RBAC & Producer Integration Tests")
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private TransactionRepository repository;

    @MockBean
    private TransactionProducer producer;

    @MockBean
    private UserRepository userRepository;

    private String userToken;
    private String clientToken;
    private String auditorToken;
    private String adminToken;

    @BeforeEach
    void setUpTokens() {
        userToken = "Bearer " + jwtUtil.generateToken("test-user", "ROLE_USER");
        clientToken = "Bearer " + jwtUtil.generateToken("test-client", "ROLE_CLIENT");
        auditorToken = "Bearer " + jwtUtil.generateToken("test-auditor", "ROLE_AUDITOR");
        adminToken = "Bearer " + jwtUtil.generateToken("test-admin", "ROLE_ADMIN");

        // Mock authorized account mappings
        when(userRepository.findByUsername("test-user")).thenReturn(Optional.of(
                new User("test-user", "hashed", "ROLE_USER", "ACC-1001")
        ));
        when(userRepository.findByUsername("test-client")).thenReturn(Optional.of(
                new User("test-client", "hashed", "ROLE_CLIENT", "ACC-2002")
        ));
    }

    @Test
    @DisplayName("1. POST /api/transactions: Saves with PENDING status and dispatches to Kafka for authorized account (ROLE_USER)")
    void createTransaction_shouldPersistPendingAndDispatchToKafka() throws Exception {
        Transaction request = new Transaction();
        request.setAccountId("ACC-1001");
        request.setAmount(BigDecimal.valueOf(175.50));
        request.setCurrency("USD");

        Transaction savedTx = new Transaction("ACC-1001", BigDecimal.valueOf(175.50), "USD");
        savedTx.setId(501L);
        savedTx.setStatus("PENDING");
        savedTx.setTimestamp(LocalDateTime.of(2026, 11, 24, 15, 0, 0));

        when(repository.save(any(Transaction.class))).thenReturn(savedTx);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(501))
                .andExpect(jsonPath("$.accountId").value("ACC-1001"))
                .andExpect(jsonPath("$.amount").value(175.50))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Verify that repository.save was called with an entity defaulting to PENDING status
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(1)).save(txCaptor.capture());
        Transaction captured = txCaptor.getValue();
        assertEquals("PENDING", captured.getStatus(), "Transaction must be initialized with PENDING status");
        assertEquals("ACC-1001", captured.getAccountId());
        assertEquals(BigDecimal.valueOf(175.50), captured.getAmount());
        assertEquals("USD", captured.getCurrency());
        assertNotNull(captured.getTimestamp(), "Timestamp must be initialized");

        // Verify that TransactionProducer received the saved transaction
        verify(producer, times(1)).sendTransactionEvent(savedTx);
    }

    @Test
    @DisplayName("2. POST /api/transactions: Allows CLIENT role for their authorized account")
    void createTransaction_shouldAllowClientRole() throws Exception {
        Transaction request = new Transaction("ACC-2002", BigDecimal.valueOf(500.00), "USD");
        Transaction saved = new Transaction("ACC-2002", BigDecimal.valueOf(500.00), "USD");
        saved.setId(502L);

        when(repository.save(any(Transaction.class))).thenReturn(saved);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(502));

        verify(producer, times(1)).sendTransactionEvent(saved);
    }

    @Test
    @DisplayName("3. POST /api/transactions (SEC-06): Rejects CLIENT attempting to transact on unauthorized account with 403 Forbidden")
    void createTransaction_withUnauthorizedAccount_shouldReturnForbidden() throws Exception {
        // test-client is authorized for ACC-2002, but attempts to transact on ACC-VICTIM-999
        Transaction request = new Transaction("ACC-VICTIM-999", BigDecimal.valueOf(500.00), "USD");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // Must not persist or produce events for unauthorized account access
        verifyNoInteractions(repository);
        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("4. POST /api/transactions (SEC-06): Allows ADMIN role to transact on any account")
    void createTransaction_withAdminRole_shouldAllowAnyAccount() throws Exception {
        Transaction request = new Transaction("ACC-ANY-ACCOUNT", BigDecimal.valueOf(1200.00), "USD");
        Transaction saved = new Transaction("ACC-ANY-ACCOUNT", BigDecimal.valueOf(1200.00), "USD");
        saved.setId(503L);

        when(repository.save(any(Transaction.class))).thenReturn(saved);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(503))
                .andExpect(jsonPath("$.accountId").value("ACC-ANY-ACCOUNT"));

        verify(repository, times(1)).save(any(Transaction.class));
        verify(producer, times(1)).sendTransactionEvent(saved);
    }

    @Test
    @DisplayName("5. POST /api/transactions: Rejects unauthenticated request with 403 Forbidden")
    void createTransaction_withoutAuth_shouldReturnForbidden() throws Exception {
        Transaction request = new Transaction("ACC-UNAUTH", BigDecimal.valueOf(100.00), "USD");

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(repository);
        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("6. POST /api/transactions: Rejects AUDITOR role from creating transactions (RBAC)")
    void createTransaction_withAuditorRole_shouldReturnForbidden() throws Exception {
        Transaction request = new Transaction("ACC-AUDITOR", BigDecimal.valueOf(100.00), "USD");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(repository);
        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("7. POST /api/transactions: Does not call Kafka producer if database persistence fails")
    void createTransaction_whenPersistenceFails_shouldNotCallProducer() throws Exception {
        Transaction request = new Transaction("ACC-1001", BigDecimal.valueOf(250.00), "USD");

        when(repository.save(any(Transaction.class)))
                .thenThrow(new DataAccessResourceFailureException("Database connection timeout"));

        assertThrows(Exception.class, () ->
                mockMvc.perform(post("/api/transactions")
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
        );

        // Crucial reliability invariant: Kafka event must never be published if DB commit fails!
        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("8. GET /api/transactions/{id}: Allows AUDITOR role to view transaction details")
    void getTransactionById_withAuditorRole_shouldReturnTransaction() throws Exception {
        Transaction tx = new Transaction("ACC-VIEW", BigDecimal.valueOf(99.00), "USD");
        tx.setId(777L);
        tx.setStatus("APPROVED");
        tx.setFraudReason("VERIFIED_CLEAN");

        when(repository.findById(777L)).thenReturn(Optional.of(tx));

        mockMvc.perform(get("/api/transactions/777")
                        .header("Authorization", auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(777))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.fraudReason").value("VERIFIED_CLEAN"));
    }

    @Test
    @DisplayName("9. GET /api/transactions/{id}: Rejects regular USER role from viewing transaction list/details (RBAC)")
    void getTransactionById_withUserRole_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/api/transactions/777")
                        .header("Authorization", userToken))
                .andExpect(status().isForbidden());

        verifyNoInteractions(repository);
    }
}
