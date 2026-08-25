package com.fintech.transaction.controller;

import com.fintech.transaction.security.JwtUtil;
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
    public Map<String, String> login(@RequestBody Map<String, String> credentials) {
        // In a real app, you would check the database for username/password here.
        // For this project, we mock authentication and generate a token directly.
        String username = credentials.get("username");
        String token = jwtUtil.generateToken(username);
        
        return Map.of("token", token);
    }
}
