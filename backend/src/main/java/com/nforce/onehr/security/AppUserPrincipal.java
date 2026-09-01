package com.nforce.onehr.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.UUID;

/**
 * Spring Security's built-in {@code org.springframework.security.core.userdetails.User} can't
 * carry extra fields, but JwtAuthenticationFilter needs the caller's live tokenVersion to detect
 * a stale (pre-role-change) JWT. Wrapping it here lets CustomUserDetailsService hand that value
 * back off the same DB row it already fetched, with no second query.
 */
@Getter
public class AppUserPrincipal implements UserDetails {

    private final UUID userId;
    private final String email;
    private final String passwordHash;
    private final int tokenVersion;
    private final String tokenVersionReason;
    private final boolean disabled;
    private final Collection<? extends GrantedAuthority> authorities;

    public AppUserPrincipal(UUID userId, String email, String passwordHash, int tokenVersion,
                             String tokenVersionReason, boolean disabled,
                             Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.tokenVersion = tokenVersion;
        this.tokenVersionReason = tokenVersionReason;
        this.disabled = disabled;
        this.authorities = authorities;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return !disabled;
    }
}
