package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "penalisation_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenalisationPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    // Section 7: the explicit, admin-chosen org-wide fallback for an employee with no allocation
    // and no legacy FK — at most one row may have this true (V152's partial unique index). See
    // PenalizationPolicyService#resolveActiveDefaultPolicyId.
    @Column(name = "is_org_default", nullable = false)
    @Builder.Default
    private boolean orgDefault = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
