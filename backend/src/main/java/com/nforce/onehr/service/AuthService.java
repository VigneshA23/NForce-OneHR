package com.nforce.onehr.service;

import com.nforce.onehr.dto.ChangePasswordRequest;
import com.nforce.onehr.dto.ChangePasswordResponse;
import com.nforce.onehr.dto.LoginRequest;
import com.nforce.onehr.dto.LoginResponse;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

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

        String roleCode = user.getRoles().stream()
                .findFirst()
                .map(com.nforce.onehr.entity.Role::getCode)
                .orElse("EMPLOYEE");

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
