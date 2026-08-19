package com.nforce.onehr.service;

import com.nforce.onehr.config.MutableClock;
import com.nforce.onehr.dto.ForgotPasswordResponse;
import com.nforce.onehr.dto.LoginRequest;
import com.nforce.onehr.dto.LoginResponse;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.exception.AccountLockedException;
import com.nforce.onehr.exception.AccountNotFoundException;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests for the 7-attempt login lockout added on top of AuthService.login()
 * (same isolation approach as HelpContentServiceTest). Uses the real {@link MutableClock} —
 * production's own testability seam — instead of a hand-rolled fake, so the lock-expiry test
 * exercises the exact clock class wired in production/E2E.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "user@test.com";
    private static final String CORRECT_PASSWORD = "correct-password";
    private static final String WRONG_PASSWORD = "wrong-password";
    private static final String PASSWORD_HASH = "bcrypt-hash";

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private NotificationService notificationService;

    private final MutableClock clock = new MutableClock();
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        clock.reset();
        authService = new AuthService(userRepository, employeeRepository, jwtTokenProvider,
                passwordEncoder, auditService, emailService, notificationService, clock);

        user = User.builder()
                .id(UUID.randomUUID())
                .email(EMAIL)
                .passwordHash(PASSWORD_HASH)
                .active(true)
                .roles(Set.of(Role.builder().code("EMPLOYEE").build()))
                .failedLoginAttempts(0)
                .build();

        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(passwordEncoder.matches(eq(CORRECT_PASSWORD), anyString())).thenReturn(true);
        lenient().when(passwordEncoder.matches(eq(WRONG_PASSWORD), anyString())).thenReturn(false);
        lenient().when(jwtTokenProvider.generateToken(anyString(), any(Boolean.class), any(Integer.class))).thenReturn("jwt-token");
    }

    private LoginRequest request(String email, String password) {
        LoginRequest r = new LoginRequest();
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    private void failedAttempt() {
        assertThrows(BadCredentialsException.class,
                () -> authService.login(request(EMAIL, WRONG_PASSWORD), "127.0.0.1", "agent"));
    }

    @Test
    void attempt1_wrongPassword_returnsInvalidCredentialsAndIncrementsCounter() {
        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> authService.login(request(EMAIL, WRONG_PASSWORD), "127.0.0.1", "agent"));

        assertEquals("Invalid credentials", ex.getMessage());
        assertEquals(1, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
        verify(auditService).log(user.getId(), "LOGIN_FAILED", user.getId());
    }

    @Test
    void attempt2_wrongPassword_incrementsToTwoAndStaysUnlocked() {
        failedAttempt();
        failedAttempt();

        assertEquals(2, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
        verify(auditService, times(2)).log(user.getId(), "LOGIN_FAILED", user.getId());
    }

    @Test
    void attempt6_wrongPassword_stillGenericErrorAndUnlocked() {
        for (int i = 0; i < 6; i++) {
            failedAttempt();
        }

        assertEquals(6, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
        verify(auditService, times(6)).log(user.getId(), "LOGIN_FAILED", user.getId());
        verify(auditService, never()).log(eq(user.getId()), eq("LOGIN_LOCKED"), any());
    }

    @Test
    void attempt7_wrongPassword_locksAccountForFourHours() {
        for (int i = 0; i < 6; i++) {
            failedAttempt();
        }
        Instant before = clock.instant();

        AccountLockedException ex = assertThrows(AccountLockedException.class,
                () -> authService.login(request(EMAIL, WRONG_PASSWORD), "127.0.0.1", "agent"));

        assertEquals(EMAIL, ex.getEmail());
        assertEquals(7, user.getFailedLoginAttempts());
        assertNotNull(user.getLockedUntil());
        assertTrue(user.getLockedUntil().isAfter(before.plus(Duration.ofHours(4).minusSeconds(5))));
        assertTrue(user.getLockedUntil().isBefore(before.plus(Duration.ofHours(4).plusSeconds(5))));
        verify(auditService).log(user.getId(), "LOGIN_LOCKED", user.getId());
        verify(auditService, times(6)).log(user.getId(), "LOGIN_FAILED", user.getId());
    }

    @Test
    void loginWhileLocked_rejectedEvenWithCorrectPassword() {
        for (int i = 0; i < 7; i++) {
            try {
                authService.login(request(EMAIL, WRONG_PASSWORD), "127.0.0.1", "agent");
            } catch (RuntimeException ignored) {
                // expected on every attempt, including the 7th (AccountLockedException)
            }
        }
        assertNotNull(user.getLockedUntil());

        AccountLockedException ex = assertThrows(AccountLockedException.class,
                () -> authService.login(request(EMAIL, CORRECT_PASSWORD), "127.0.0.1", "agent"));

        assertEquals(EMAIL, ex.getEmail());
        // Locked account is rejected before any password comparison — the correct password
        // must never be evaluated while locked.
        verify(passwordEncoder, never()).matches(eq(CORRECT_PASSWORD), anyString());
    }

    @Test
    void successfulLogin_resetsFailedAttemptCounter() {
        failedAttempt();
        failedAttempt();
        failedAttempt();
        assertEquals(3, user.getFailedLoginAttempts());

        LoginResponse response = authService.login(request(EMAIL, CORRECT_PASSWORD), "127.0.0.1", "agent");

        assertEquals("jwt-token", response.getToken());
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
        verify(auditService).log(user.getId(), "LOGIN_SUCCESS", user.getId());
    }

    @Test
    void lockExpiry_afterFourHoursLoginIsAllowedAndCounterResets() {
        for (int i = 0; i < 7; i++) {
            try {
                authService.login(request(EMAIL, WRONG_PASSWORD), "127.0.0.1", "agent");
            } catch (RuntimeException ignored) {
                // expected
            }
        }
        assertNotNull(user.getLockedUntil());

        // Still within the lock window: rejected.
        clock.advanceBy(Duration.ofHours(3).plusMinutes(59));
        assertThrows(AccountLockedException.class,
                () -> authService.login(request(EMAIL, CORRECT_PASSWORD), "127.0.0.1", "agent"));

        // Past the 4-hour window: allowed, and the counter auto-resets.
        clock.advanceBy(Duration.ofMinutes(2));
        LoginResponse response = authService.login(request(EMAIL, CORRECT_PASSWORD), "127.0.0.1", "agent");

        assertEquals("jwt-token", response.getToken());
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void unknownEmail_returnsGenericInvalidCredentialsAndDoesNotTouchAudit() {
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> authService.login(request("nobody@test.com", "whatever"), "127.0.0.1", "agent"));

        assertEquals("Invalid credentials", ex.getMessage());
        verify(passwordEncoder).matches(eq("whatever"), anyString());
        verifyNoInteractions(auditService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void deactivatedAccount_correctPasswordStillBlockedAndAttemptsUnaffected() {
        user.setActive(false);

        DisabledException ex = assertThrows(DisabledException.class,
                () -> authService.login(request(EMAIL, CORRECT_PASSWORD), "127.0.0.1", "agent"));

        assertEquals("Account has been deactivated", ex.getMessage());
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
        verify(auditService).log(user.getId(), "LOGIN_BLOCKED", user.getId());
    }

    @Test
    void forgotPassword_registeredActiveEmail_sendsResetEmailAndReturnsSuccessMessage() {
        when(employeeRepository.findById(user.getId())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");

        ForgotPasswordResponse response = authService.forgotPassword(EMAIL.toUpperCase(), "http://localhost:5180");

        assertEquals("Password reset instructions have been sent to your email", response.getMessage());
        assertTrue(user.isMustChangePassword());
        verify(emailService).sendPasswordResetEmail(eq(EMAIL), eq(EMAIL), anyString(), eq("http://localhost:5180"));
        verify(auditService).log(user.getId(), "PASSWORD_RESET_VIA_FORGOT_FLOW", user.getId());
        verify(notificationService).send(eq(user.getId()), eq("SECURITY"), anyString(), anyString(), anyString());
    }

    @Test
    void forgotPassword_unregisteredEmail_throwsAccountNotFound() {
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        AccountNotFoundException ex = assertThrows(AccountNotFoundException.class,
                () -> authService.forgotPassword("nobody@test.com", "http://localhost:5180"));

        assertEquals("No account found with this email address", ex.getMessage());
        verifyNoInteractions(emailService);
        verifyNoInteractions(notificationService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void forgotPassword_deactivatedAccount_throwsDisabledAndSendsNoEmail() {
        user.setActive(false);

        DisabledException ex = assertThrows(DisabledException.class,
                () -> authService.forgotPassword(EMAIL, "http://localhost:5180"));

        assertEquals("This account has been deactivated. Please contact your HR administrator", ex.getMessage());
        verifyNoInteractions(emailService);
        verifyNoInteractions(notificationService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void forgotPassword_deletedAccount_throwsDisabledAndSendsNoEmail() {
        user.setDeletedAt(Instant.now());

        DisabledException ex = assertThrows(DisabledException.class,
                () -> authService.forgotPassword(EMAIL, "http://localhost:5180"));

        assertEquals("This account has been deleted. Please contact your HR administrator", ex.getMessage());
        verifyNoInteractions(emailService);
        verifyNoInteractions(notificationService);
        verify(userRepository, never()).save(any());
    }
}
