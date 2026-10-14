package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** A role's grant of one Permission, at one Scope. See V224__create_permission_tables.sql for
 *  the scope meanings and the "widest scope wins across a user's held roles" resolution rule
 *  (that resolution lives in application code, not here — this is just the stored grant). */
@Entity
@Table(name = "role_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RolePermission {

    public static final String OWN = "OWN";
    public static final String TEAM = "TEAM";
    public static final String DEPARTMENT = "DEPARTMENT";
    public static final String LOCATION = "LOCATION";
    public static final String ALL = "ALL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    @Column(nullable = false, length = 20)
    private String scope;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private LocalDateTime grantedAt;

    @PrePersist
    protected void onCreate() {
        grantedAt = LocalDateTime.now();
    }
}
