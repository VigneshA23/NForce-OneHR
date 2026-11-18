package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A saved state of a role (name, description, active flag, full authority list) after one change; restorable. */
@Entity
@Table(name = "role_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_id", nullable = false)
    private Integer roleId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean active;

    /** "module:ACTION:SCOPE" strings, every grant the role held right after this change. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> permissions;

    @Column(name = "change_type", nullable = false, length = 20)
    private String changeType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(length = 500)
    private String reason;

    @Column(name = "restored_from_version")
    private Integer restoredFromVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
