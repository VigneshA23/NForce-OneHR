package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One (module, action) pair — e.g. (attendance, APPROVE). Actions are deliberately a plain
 *  VARCHAR + CHECK constraint (see V224), not a native Postgres enum, matching this codebase's
 *  existing convention for enum-like columns (e.g. SkillMigrationCandidate.status). */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Permission {

    public static final String VIEW = "VIEW";
    public static final String CREATE = "CREATE";
    public static final String EDIT = "EDIT";
    public static final String DELETE = "DELETE";
    public static final String APPROVE = "APPROVE";
    public static final String REVERT = "REVERT";
    public static final String EXPORT = "EXPORT";
    public static final String CONFIGURE = "CONFIGURE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private PermissionModule module;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
