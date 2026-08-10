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

    // Only the DB id participates in equals/hashCode — Role rows are only ever created by the
    // Flyway seed migration (never constructed transient at runtime), so id is always present
    // once persisted. Without this, Set<Role> falls back to JVM identity hashing, which makes
    // Set iteration order (and thus any findFirst() over it) different on every fresh JPA load.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer id;

    // Stable machine code (e.g. 'SUPER_ADMIN') — never changes after seed
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "can_manage_employee_records", nullable = false)
    @Builder.Default
    private boolean canManageEmployeeRecords = false;

    @Column(name = "can_promote_users", nullable = false)
    @Builder.Default
    private boolean canPromoteUsers = false;

    @Column(name = "can_reset_any_password", nullable = false)
    @Builder.Default
    private boolean canResetAnyPassword = false;

    @Column(name = "can_deactivate_users", nullable = false)
    @Builder.Default
    private boolean canDeactivateUsers = false;

    @Column(name = "is_phase1", nullable = false)
    @Builder.Default
    private boolean phase1 = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
