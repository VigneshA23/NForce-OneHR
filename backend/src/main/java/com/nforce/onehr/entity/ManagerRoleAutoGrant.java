package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** Marker row: this user's MANAGER role was granted automatically (first direct report gained),
 *  not manually assigned by a Super Admin — see V226__create_manager_role_auto_grants.sql for the
 *  full rationale. Presence drives whether {@code ManagerRoleSyncService} may auto-revoke MANAGER
 *  when this user's last direct report leaves. */
@Entity
@Table(name = "manager_role_auto_grants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ManagerRoleAutoGrant {

    @Id
    @EqualsAndHashCode.Include
    private UUID userId;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private LocalDateTime grantedAt;

    @PrePersist
    protected void onCreate() {
        grantedAt = LocalDateTime.now();
    }
}
