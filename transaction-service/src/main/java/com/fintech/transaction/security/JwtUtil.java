package com.fintech.transaction.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.List;

@Component
public class JwtUtil {

    public static final int MINIMUM_SECRET_BYTES = 32; // 256 bits for HMAC-SHA256

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expirationTime;

    private Key signingKey;

    @PostConstruct
    public void init() {
        validateAndInitializeKey();
    }

    /**
     * Validates that the configured JWT signing secret is present and satisfies
     * the cryptographic minimum length requirement of at least 256 bits (32 bytes).
     * Fails fast during application startup to prevent running with insecure defaults.
     */
    public synchronized void validateAndInitializeKey() {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("JWT secret configuration ('jwt.secret') must be set and cannot be blank");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    String.format("JWT secret is too short (%d bytes). Must be at least %d bytes (256 bits) for HMAC-SHA256",
                            keyBytes.length, MINIMUM_SECRET_BYTES)
            );
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    private Key getSigningKey() {
        if (this.signingKey == null) {
            validateAndInitializeKey();
        }
        return this.signingKey;
    }

    public String generateToken(String username, String role) {
        return Jwts.builder()
                .setSubject(username)
                .claim("roles", List.of(role))
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Claims claims = extractAllClaims(token);
        Object roles = claims.get("roles");
        if (roles instanceof List<?>) {
            return (List<String>) roles;
        }
        return List.of("ROLE_USER");
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
