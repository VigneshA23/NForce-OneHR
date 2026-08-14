package com.nforce.onehr.service;

import com.nforce.onehr.dto.*;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.exception.AccountLockedException;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.JwtTokenProvider;
import com.nforce.onehr.util.RoleUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final Clock clock;

    private static final String GENERIC_CRED_ERROR = "Invalid credentials";
    private static final int MAX_FAILED_ATTEMPTS = 7;
    private static final Duration LOCK_DURATION = Duration.ofHours(4);

    // Attempts 1-6 return the generic "Invalid credentials" 401; noRollbackFor is required
    // because both branches persist failed-attempt/lockout state on the user row via
    // userRepository.save(), and Spring would otherwise roll that back along with the rest of
    // the transaction when the method exits via one of these exceptions.
    @Transactional(noRollbackFor = {BadCredentialsException.class, AccountLockedException.class})
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim()).orElse(null);

        if (user != null && isCurrentlyLocked(user)) {
            throw new AccountLockedException(user.getEmail(), user.getLockedUntil());
        }

        // Always run bcrypt even when user not found — prevents timing-based user enumeration
        String candidateHash = user != null
                ? user.getPasswordHash()
                : "$2b$10$LxaD8jQMsa8UGDvF4uiIwOvGJFcss7qLGGdChmxhvfiL7f9ylIxje";
        boolean credentialsValid = passwordEncoder.matches(request.getPassword(), candidateHash);

        if (user == null || !credentialsValid) {
            if (user != null) {
                registerFailedAttempt(user);
            }
            throw new BadCredentialsException(GENERIC_CRED_ERROR);
        }

        if (!user.isActive()) {
            auditService.log(user.getId(), "LOGIN_BLOCKED", user.getId());
            throw new DisabledException("Account has been deactivated");
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        String token = jwtTokenProvider.generateToken(user.getEmail(), user.isMustChangePassword());
        auditService.log(user.getId(), "LOGIN_SUCCESS", user.getId());

        String roleCode = RoleUtils.primaryRoleCode(user.getRoles(), "EMPLOYEE");

        return LoginResponse.builder()
                .token(token)
                .mustChangePassword(user.isMustChangePassword())
                .email(user.getEmail())
                .role(roleCode)
                .build();
    }

    // Returns whether the user is still within an active lock window. A lock whose expiry has
    // already passed is cleared here (and the attempt counter reset) so the very next login
    // attempt after 4 hours is processed normally, per the auto-expiry requirement.
    private boolean isCurrentlyLocked(User user) {
        Instant lockedUntil = user.getLockedUntil();
        if (lockedUntil == null) {
            return false;
        }
        if (clock.instant().isBefore(lockedUntil)) {
            return true;
        }
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        userRepository.save(user);
        return false;
    }

    private void registerFailedAttempt(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            Instant lockedUntil = clock.instant().plus(LOCK_DURATION);
            user.setLockedUntil(lockedUntil);
            userRepository.save(user);
            auditService.log(user.getId(), "LOGIN_LOCKED", user.getId());
            throw new AccountLockedException(user.getEmail(), lockedUntil);
        }

        userRepository.save(user);
        auditService.log(user.getId(), "LOGIN_FAILED", user.getId());
    }

    @Transactional
    public ChangePasswordResponse changePassword(ChangePasswordRequest request,
                                                  String currentUserEmail) {
        User user = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (!user.isActive()) {
            throw new DisabledException("Account has been deactivated");
        }

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            auditService.log(user.getId(), "PASSWORD_CHANGE_FAILED", user.getId());
            throw new BadCredentialsException("Current password is incorrect");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("New password and confirmation do not match");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from current password");
        }

        validatePasswordStrength(request.getNewPassword());

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);

        auditService.log(user.getId(), "PASSWORD_CHANGED", user.getId());

        String newToken = jwtTokenProvider.generateToken(user.getEmail(), false);

        return ChangePasswordResponse.builder()
                .token(newToken)
                .message("Password changed successfully")
                .build();
    }

    /**
     * Forgot-password flow: validates the account status up front and reports it back
     * distinctly (no account found / deactivated / deleted / reset sent) rather than a single
     * generic response — a deliberate product decision to prioritize clear self-service
     * feedback over email-enumeration hardening for this flow.
     */
    @Transactional
    public ForgotPasswordResponse forgotPassword(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("No account found with this email address"));

        if (user.getDeletedAt() != null) {
            throw new DisabledException("This account has been deleted. Please contact your HR administrator");
        }
        if (!user.isActive()) {
            throw new DisabledException("This account has been deactivated. Please contact your HR administrator");
        }

        String tempPassword = generateTempPassword();
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);

        String fullName = employeeRepository.findById(user.getId())
                .map(com.nforce.onehr.entity.Employee::getFullName)
                .orElse(user.getEmail());

        emailService.sendPasswordResetEmail(user.getEmail(), fullName, tempPassword);
        auditService.log(user.getId(), "PASSWORD_RESET_VIA_FORGOT_FLOW", user.getId());
        notificationService.send(user.getId(), "SECURITY",
                "Password Reset",
                "Your password was reset via the forgot-password flow. If you didn't request this, contact your HR admin immediately.",
                "/change-password");

        return ForgotPasswordResponse.builder()
                .message("Password reset instructions have been sent to your email")
                .build();
    }

    private String generateTempPassword() {
        int digits = 100000 + RANDOM.nextInt(900000);
        return "OneHR@" + digits;
    }

    private void validatePasswordStrength(String password) {
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        int score = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) score++;
        if (password.chars().anyMatch(Character::isLowerCase)) score++;
        if (password.chars().anyMatch(Character::isDigit)) score++;
        if (password.chars().anyMatch(c -> "!@#$%^&*()_+-=[]{}|;':\",./<>?".indexOf(c) >= 0)) score++;

        if (score < 3) {
            throw new IllegalArgumentException(
                    "Password must contain at least 3 of: uppercase letter, lowercase letter, number, special character");
        }
    }
}
