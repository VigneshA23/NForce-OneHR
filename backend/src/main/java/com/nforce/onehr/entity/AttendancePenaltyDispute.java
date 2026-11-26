package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** PN-02: an employee's contest of one {@link AttendancePenalty}. See V316 for the lifecycle. */
@Entity
@Table(name = "attendance_penalty_disputes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendancePenaltyDispute {

    public static final String PENDING_MANAGER = "PENDING_MANAGER";
    public static final String PENDING_HR = "PENDING_HR";
    public static final String UPHELD = "UPHELD";
    public static final String REVERSED = "REVERSED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "penalty_id", nullable = false)
    private UUID penaltyId;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    // Free-text description of the supporting evidence (e.g. "biometric device down 09:00-10:30, ticket IT-4821").
    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = PENDING_MANAGER;

    @Column(name = "manager_user_id")
    private UUID managerUserId;

    // UPHOLD or REVERSE - the manager's recommendation to HR, not a final decision.
    @Column(name = "manager_recommendation", length = 10)
    private String managerRecommendation;

    @Column(name = "manager_comment", columnDefinition = "TEXT")
    private String managerComment;

    @Column(name = "manager_acted_at")
    private LocalDateTime managerActedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolution_comment", columnDefinition = "TEXT")
    private String resolutionComment;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
