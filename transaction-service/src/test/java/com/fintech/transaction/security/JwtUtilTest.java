package com.fintech.transaction.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Transaction Service - JWT Token Security Unit Tests")
class JwtUtilTest {

    private JwtUtil jwtUtil;
    private static final String SECRET_KEY = "c3VwZXItc2VjcmV0LWZpbnRlY2gta2V5LWZvci1qd3QtYXV0aC1lbnRlcnByaXNl"; // >= 256 bits
    private static final long EXPIRATION_MS = 86400000L; // 24 hours

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET_KEY);
        ReflectionTestUtils.setField(jwtUtil, "expirationTime", EXPIRATION_MS);
    }

    @Test
    @DisplayName("Should generate a valid HMAC-SHA256 JWT token for an authorized user")
    void shouldGenerateValidToken() {
        String token = jwtUtil.generateToken("analyst_pramodh", "ROLE_ADMIN");

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    @DisplayName("Should extract username matching the subject claim")
    void shouldExtractUsername() {
        String token = jwtUtil.generateToken("analyst_pramodh", "ROLE_ADMIN");
        String username = jwtUtil.extractUsername(token);

        assertEquals("analyst_pramodh", username);
    }

    @Test
    @DisplayName("Should extract assigned roles matching the roles claim")
    void shouldExtractRoles() {
        String token = jwtUtil.generateToken("customer_44", "ROLE_USER");
        List<String> roles = jwtUtil.extractRoles(token);

        assertNotNull(roles);
        assertEquals(1, roles.size());
        assertEquals("ROLE_USER", roles.get(0));
    }

    @Test
    @DisplayName("Should reject malformed or tampered token")
    void shouldRejectMalformedToken() {
        String invalidToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.invalid.signature";
        assertFalse(jwtUtil.validateToken(invalidToken));
    }
}
