package com.fintech.transaction.controller;

import com.fintech.transaction.dto.LoginRequest;
import com.fintech.transaction.model.User;
import com.fintech.transaction.repository.UserRepository;
import com.fintech.transaction.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null || request.username().trim().isEmpty()
                || request.password() == null || request.password().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }

        String username = request.username().trim();
        Optional<User> userOpt = userRepository.findByUsername(username);

        // Constant-time style / generic check: verify user exists and BCrypt password matches
        if (userOpt.isEmpty() || !passwordEncoder.matches(request.password(), userOpt.get().getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        User user = userOpt.get();

        // Retrieve role strictly from the persistent User entity in the database, NEVER from client request
        String role = user.getRole();
        if (role == null || role.trim().isEmpty()) {
            role = "ROLE_USER";
        } else if (!role.startsWith("ROLE_")) {
            role = "ROLE_" + role.toUpperCase();
        }

        String token = jwtUtil.generateToken(user.getUsername(), role);

        return ResponseEntity.ok(Map.of(
            "token", token,
            "username", user.getUsername(),
            "role", role,
            "expiresIn", "24 hours"
        ));
    }
}
