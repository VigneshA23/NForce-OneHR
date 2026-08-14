package com.nforce.onehr.exception;

import lombok.Getter;

import java.time.Instant;

/**
 * Thrown when a login attempt targets an account currently under the 7-failed-attempt
 * lockout. Distinct from {@link org.springframework.security.authentication.BadCredentialsException}
 * so the API can surface a different HTTP status and the frontend can render a locked-account
 * state instead of a generic invalid-credentials error.
 */
@Getter
public class AccountLockedException extends RuntimeException {

    private final String email;
    private final Instant lockedUntil;

    public AccountLockedException(String email, Instant lockedUntil) {
        super("Account is locked");
        this.email = email;
        this.lockedUntil = lockedUntil;
    }
}
