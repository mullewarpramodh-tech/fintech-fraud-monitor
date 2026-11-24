package com.fintech.transaction.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("User Entity Constraint and Validation Tests")
class UserTest {

    @Test
    @DisplayName("Should construct valid User entity and trim inputs")
    void shouldConstructValidUser() {
        User user = new User("  admin_user  ", "bcrypt_hash_value", "  ROLE_ADMIN  ", "  ACC-ADMIN-01  ");

        assertEquals("admin_user", user.getUsername());
        assertEquals("bcrypt_hash_value", user.getPassword());
        assertEquals("ROLE_ADMIN", user.getRole());
        assertEquals("ACC-ADMIN-01", user.getAccountId());
    }

    @Test
    @DisplayName("Should support 3-argument constructor with null accountId")
    void shouldConstructUserWithoutAccountId() {
        User user = new User("client_user", "hash", "ROLE_CLIENT");

        assertEquals("client_user", user.getUsername());
        assertNull(user.getAccountId());

        user.setAccountId("ACC-CLIENT-99");
        assertEquals("ACC-CLIENT-99", user.getAccountId());
    }

    @Test
    @DisplayName("Should reject null or blank username")
    void shouldRejectNullOrBlankUsername() {
        assertThrows(IllegalArgumentException.class, () -> new User(null, "hash", "ROLE_USER"));
        assertThrows(IllegalArgumentException.class, () -> new User("   ", "hash", "ROLE_USER"));
    }

    @Test
    @DisplayName("Should reject null or blank password")
    void shouldRejectNullOrBlankPassword() {
        assertThrows(IllegalArgumentException.class, () -> new User("user1", null, "ROLE_USER"));
        assertThrows(IllegalArgumentException.class, () -> new User("user1", "   ", "ROLE_USER"));
    }

    @Test
    @DisplayName("Should reject null or blank role")
    void shouldRejectNullOrBlankRole() {
        assertThrows(IllegalArgumentException.class, () -> new User("user1", "hash", null));
        assertThrows(IllegalArgumentException.class, () -> new User("user1", "hash", "   "));
    }
}
