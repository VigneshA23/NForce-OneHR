package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssetRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "category_id", nullable = false)
    private Integer categoryId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    // PENDING | APPROVED | REJECTED | FULFILLED
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "manager_decided_by")
    private UUID managerDecidedBy;

    @Column(name = "manager_decided_at")
    private Instant managerDecidedAt;

    @Column(name = "fulfilled_by")
    private UUID fulfilledBy;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }
}
