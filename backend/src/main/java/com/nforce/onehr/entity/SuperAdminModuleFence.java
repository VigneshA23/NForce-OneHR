package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** One (module, action) pair the Account Owner has fenced off from Super Admin — see
 *  V228__account_owner_and_super_admin_fences.sql for the full design rationale. {@code action}
 *  is either a real {@link Permission#getAction()} value or the wildcard {@code "*"} (every
 *  action on this module, present and future). Presence of a row here means "no account holding
 *  SUPER_ADMIN may exercise this grant, no matter which of its held roles supplied it" — see
 *  {@link com.nforce.onehr.security.EffectivePermissionService}. */
@Entity
@Table(name = "super_admin_module_fences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SuperAdminModuleFence {

    public static final String ALL_ACTIONS = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private PermissionModule module;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(name = "fenced_at", nullable = false, updatable = false)
    private LocalDateTime fencedAt;

    @Column(name = "fenced_by", nullable = false)
    private UUID fencedBy;

    @PrePersist
    protected void onCreate() {
        fencedAt = LocalDateTime.now();
    }
}
