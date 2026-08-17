package com.nforce.onehr.exception;

/**
 * Thrown by the forgot-password flow when the submitted email does not match any account.
 * Deliberately distinct from {@link org.springframework.security.core.userdetails.UsernameNotFoundException}
 * (which login/changePassword mask as a generic invalid-credentials response) — forgot-password
 * is expected to tell the user their email isn't registered, per product requirements.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
