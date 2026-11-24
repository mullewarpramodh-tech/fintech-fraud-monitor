package com.fintech.transaction.model;

import jakarta.persistence.*;

/**
 * Persistent user entity for authentication and authorization.
 * Stores BCrypt-hashed credentials, assigned system roles, and authorized account mapping.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 30)
    private String role;

    @Column(length = 50)
    private String accountId;

    public User() {}

    public User(String username, String password, String role) {
        this(username, password, role, null);
    }

    public User(String username, String password, String role, String accountId) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username must not be null or blank");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Password must not be null or blank");
        }
        if (role == null || role.trim().isEmpty()) {
            throw new IllegalArgumentException("Role must not be null or blank");
        }
        this.username = username.trim();
        this.password = password;
        this.role = role.trim();
        this.accountId = accountId != null && !accountId.trim().isEmpty() ? accountId.trim() : null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
