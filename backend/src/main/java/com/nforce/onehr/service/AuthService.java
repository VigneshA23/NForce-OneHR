package com.nforce.onehr.service;

import com.nforce.onehr.dto.*;
import com.nforce.onehr.entity.User;
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

    private static final String GENERIC_CRED_ERROR = "Invalid credentials";

    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim()).orElse(null);

        // Always run bcrypt even when user not found — prevents timing-based user enumeration
        String candidateHash = user != null
                ? user.getPasswordHash()
                : "$2b$10$LxaD8jQMsa8UGDvF4uiIwOvGJFcss7qLGGdChmxhvfiL7f9ylIxje";
        boolean credentialsValid = passwordEncoder.matches(request.getPassword(), candidateHash);

        if (user == null || !credentialsValid) {
            if (user != null) {
                auditService.log(user.getId(), "LOGIN_FAILED", user.getId());
            }
            throw new BadCredentialsException(GENERIC_CRED_ERROR);
        }

        if (!user.isActive()) {
            auditService.log(user.getId(), "LOGIN_BLOCKED", user.getId());
            throw new DisabledException("Account has been deactivated");
        }

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
     * Forgot-password flow: always returns generic success regardless of whether the email exists.
     * Prevents email enumeration — response shape and timing must not reveal account existence.
     */
    @Transactional
    public ForgotPasswordResponse forgotPassword(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            if (user.isActive() && user.getDeletedAt() == null) {
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
            }
        });
        return ForgotPasswordResponse.builder()
                .message("If that email is registered, we've sent password reset instructions.")
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
