package com.fintech.transaction;

import com.fintech.transaction.model.User;
import com.fintech.transaction.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Demo User Seeding Profile Gating & Static Asset Security Tests")
class DemoUserSeedingProfileTest {

    @Test
    @DisplayName("1. Profile Gating: Verifies initDefaultUsers is strictly gated to 'dev' and 'local' profiles")
    void verifyInitDefaultUsersIsGatedByDevAndLocalProfiles() throws NoSuchMethodException {
        Method method = TransactionServiceApplication.class.getMethod(
                "initDefaultUsers", UserRepository.class, PasswordEncoder.class
        );

        Profile profile = method.getAnnotation(Profile.class);
        assertNotNull(profile, "initDefaultUsers method must be annotated with @Profile");
        assertArrayEquals(new String[]{"dev", "local"}, profile.value(),
                "initDefaultUsers must only be active under 'dev' and 'local' profiles to protect production");
    }

    @Test
    @DisplayName("2. Execution Logic: Seeds default users when database is empty")
    void shouldSeedDefaultUsersWhenDatabaseIsEmpty() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("hashed_pass");

        TransactionServiceApplication app = new TransactionServiceApplication();
        CommandLineRunner runner = app.initDefaultUsers(userRepository, passwordEncoder);

        runner.run();

        // Must seed admin, auditor, client, and user (4 calls)
        verify(userRepository, times(4)).save(any(User.class));
    }

    @Test
    @DisplayName("3. Execution Logic: Does NOT seed users if database already contains users")
    void shouldNotSeedUsersWhenDatabaseIsNotEmpty() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(userRepository.count()).thenReturn(5L);

        TransactionServiceApplication app = new TransactionServiceApplication();
        CommandLineRunner runner = app.initDefaultUsers(userRepository, passwordEncoder);

        runner.run();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("4. Static Dashboard: Verifies index.html has no hard-coded credentials")
    void verifyIndexHtmlHasNoHardcodedCredentials() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"));
        assertFalse(html.contains("admin123"), "index.html must not contain default password 'admin123'");
        assertFalse(html.contains("password: 'admin'"), "index.html must not contain hard-coded password");
        assertFalse(html.contains("password: \"admin\""), "index.html must not contain hard-coded password");
        assertTrue(html.contains("handleLogin"), "index.html must contain interactive handleLogin function");
        assertTrue(html.contains("handleLogout"), "index.html must contain interactive handleLogout function");
        assertTrue(html.contains("login-username"), "index.html must provide username input");
        assertTrue(html.contains("login-password"), "index.html must provide password input");
    }
}
