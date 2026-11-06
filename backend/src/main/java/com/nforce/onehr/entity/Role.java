package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Role {

    // Only the DB id participates in equals/hashCode — system roles come from the Flyway seed and
    // custom roles are saved before they're ever put in a Set (RoleAdminService#create), so id is
    // always present once a Role is compared. Without this, Set<Role> falls back to JVM identity hashing, which makes
    // Set iteration order (and thus any findFirst() over it) different on every fresh JPA load.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer id;

    // Stable machine code (e.g. 'SUPER_ADMIN', or 'CUSTOM_PAYROLL_VIEWER' for a custom role) —
    // never changes after creation, even when display_name is renamed
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "is_phase1", nullable = false)
    @Builder.Default
    private boolean phase1 = false;

    // TRUE for the 7 seeded roles (V232), FALSE for admin-created custom roles. System roles can
    // never be renamed or deleted; SUPER_ADMIN's permissions are locked too — see RoleAdminService.
    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private boolean system = false;

    @Column(length = 255)
    private String description;

    // An inactive role stays assigned but contributes no authorities — see
    // EffectivePermissionService#activeRoles. Only custom roles can be deactivated.
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Optimistic lock for Roles & Permissions edits (V240): every save bumps it, and the editor
    // sends back the version it loaded, so a second admin's stale save is rejected instead of
    // silently overwriting the first (see RoleAdminService#requireVersion).
    @Version
    @Column(nullable = false)
    @Builder.Default
    private int version = 0;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
