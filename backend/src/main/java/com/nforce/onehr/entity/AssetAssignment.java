package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_assignments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssetAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    // NULL = this is the current (open) assignment
    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "return_condition", length = 20)
    private String returnCondition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (effectiveFrom == null) effectiveFrom = createdAt;
    }
}
