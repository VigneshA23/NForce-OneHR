package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** A business-level area of the app (e.g. 'attendance', 'organization') that permissions are
 *  scoped to. Named PermissionModule rather than Module to avoid colliding with the
 *  auto-imported java.lang.Module (Java's own module-system type). See
 *  V224__create_permission_tables.sql for the full design rationale. */
@Entity
@Table(name = "modules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PermissionModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer id;

    // Stable machine code (e.g. 'attendance') — never changes after seed
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    // Whether role permissions for this module can be configured in Roles & Permissions — TRUE only
    // where access is decided purely by hasAuthority(...). See V232 for the rationale.
    @Column(name = "custom_role_ready", nullable = false)
    @Builder.Default
    private boolean customRoleReady = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
