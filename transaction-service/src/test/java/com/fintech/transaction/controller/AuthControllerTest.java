package com.fintech.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.transaction.config.SecurityConfig;
import com.fintech.transaction.dto.LoginRequest;
import com.fintech.transaction.model.User;
import com.fintech.transaction.repository.UserRepository;
import com.fintech.transaction.security.JwtAuthFilter;
import com.fintech.transaction.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class})
@DisplayName("Authentication Controller — Login & RBAC Security Tests")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        // Pre-create simulated users with BCrypt-hashed passwords
        User adminUser = new User("admin_user", passwordEncoder.encode("correct_admin_pass"), "ROLE_ADMIN");
        User clientUser = new User("client_user", passwordEncoder.encode("client_secret"), "ROLE_CLIENT");
        User auditorUser = new User("auditor_user", passwordEncoder.encode("auditor_secret"), "ROLE_AUDITOR");

        when(userRepository.findByUsername("admin_user")).thenReturn(Optional.of(adminUser));
        when(userRepository.findByUsername("client_user")).thenReturn(Optional.of(clientUser));
        when(userRepository.findByUsername("auditor_user")).thenReturn(Optional.of(auditorUser));
        when(userRepository.findByUsername("unknown_user")).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("1. Successful Login: Valid username and password returns 200 OK with signed JWT and correct DB role")
    void login_withValidCredentials_shouldReturnJwtToken() throws Exception {
        LoginRequest request = new LoginRequest("admin_user", "correct_admin_pass");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.username").value("admin_user"))
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.expiresIn").value("24 hours"));

        verify(userRepository, times(1)).findByUsername("admin_user");
    }

    @Test
    @DisplayName("2. Invalid Password: Wrong password returns 401 Unauthorized without issuing token")
    void login_withInvalidPassword_shouldReturnUnauthorized() throws Exception {
        LoginRequest request = new LoginRequest("admin_user", "wrong_password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("3. Unknown User: Non-existent username returns 401 Unauthorized without issuing token")
    void login_withUnknownUser_shouldReturnUnauthorized() throws Exception {
        LoginRequest request = new LoginRequest("unknown_user", "any_password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"))
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(userRepository, times(1)).findByUsername("unknown_user");
    }

    @Test
    @DisplayName("4. Role Assignment: Retrieves role strictly from database, ignoring client-supplied role injection")
    void login_withClientSuppliedRole_shouldIgnoreClientRoleAndUseDatabaseRole() throws Exception {
        // Attempt privilege escalation: client_user tries to supply role 'ADMIN'
        Map<String, String> maliciousPayload = Map.of(
                "username", "client_user",
                "password", "client_secret",
                "role", "ADMIN"
        );

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.username").value("client_user"))
                .andExpect(jsonPath("$.role").value("ROLE_CLIENT")); // Must remain ROLE_CLIENT from DB!
    }

    @Test
    @DisplayName("5. Missing Credentials: Empty username or password returns 400 Bad Request")
    void login_withMissingCredentials_shouldReturnBadRequest() throws Exception {
        LoginRequest emptyPassRequest = new LoginRequest("admin_user", "");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyPassRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Username and password are required"));

        verifyNoInteractions(userRepository);
    }
}
