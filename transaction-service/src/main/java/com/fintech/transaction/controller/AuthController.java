package com.fintech.transaction.controller;

import com.fintech.transaction.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtUtil jwtUtil;

    public AuthController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String role = credentials.getOrDefault("role", "ROLE_USER");

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }

        // Standardize Spring Security role prefix
        if (!role.startsWith("ROLE_")) {
            role = "ROLE_" + role.toUpperCase();
        }

        String token = jwtUtil.generateToken(username, role);
        
        return ResponseEntity.ok(Map.of(
            "token", token,
            "username", username,
            "role", role,
            "expiresIn", "24 hours"
        ));
    }
}
