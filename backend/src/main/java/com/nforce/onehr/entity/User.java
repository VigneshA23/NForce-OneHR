package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // CITEXT in PostgreSQL — case-insensitive equal comparison at DB level
    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    // The hash being replaced, kept so a password change/reset can reject reuse of the
    // immediately-preceding password, not just the current one. Null for an account whose
    // password has never been changed. See #updatePasswordHash.
    @Column(name = "previous_password_hash", length = 255)
    private String previousPasswordHash;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;

    @Column(name = "deleted_at", columnDefinition = "TIMESTAMPTZ")
    private Instant deletedAt;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until", columnDefinition = "TIMESTAMPTZ")
    private Instant lockedUntil;

    // Bumped whenever a Super Admin changes this user's profile through
    // UserManagementService#updateUser so any already-issued JWT — which carries the version it
    // was minted with — fails the check in JwtAuthenticationFilter on this user's very next
    // request, without needing a session store.
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private int tokenVersion = 0;

    // Why the tokenVersion above was last bumped — "PASSWORD_CHANGED" or "PROFILE_UPDATED" (see
    // AuthService#changePassword/forgotPassword, UserManagementService#resetPassword/updateUser).
    // Read by JwtAuthenticationFilter to tell the frontend which forced-logout message to show;
    // null for rows bumped before this field existed or by any path that doesn't set it.
    @Column(name = "token_version_reason", length = 32)
    private String tokenVersionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Shifts the current hash into previousPasswordHash before replacing it, so reuse checks
     * can always see the one password being superseded — call this instead of setPasswordHash
     * whenever the password is actually being changed (not initial account creation). */
    public void updatePasswordHash(String newHash) {
        this.previousPasswordHash = this.passwordHash;
        this.passwordHash = newHash;
    }
}
