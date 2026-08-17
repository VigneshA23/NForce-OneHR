package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A penalty applied against one employee for one attendance discrepancy on one date. There is
 * no production trigger that creates these today — see
 * {@link com.nforce.onehr.service.AttendancePenaltyEvaluationService} for why. The policy fields
 * are a point-in-time snapshot: if the policy version that produced this row is later superseded,
 * this row is never retroactively recalculated (see {@link com.nforce.onehr.entity.PenalizationPolicyVersion}).
 */
@Entity
@Table(name = "attendance_penalties")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendancePenalty {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    // The attendance date the discrepancy occurred on — not the date the penalty was recorded.
    @Column(name = "incident_date", nullable = false)
    private LocalDate incidentDate;

    // One of the ExceptionType constants — a discrepancy classification, not a policy rule.
    @Column(name = "discrepancy_type", nullable = false, length = 30)
    private String discrepancyType;

    // PENDING_REVIEW, APPLIED, CANCELLED, REVERSED — see AttendancePenaltyStatus.
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = AttendancePenaltyStatus.PENDING_REVIEW;

    // ── Policy snapshot at evaluation time — never recomputed later ──
    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "policy_version")
    private Integer policyVersion;

    // The matched rule's configured leave-deduction amount at evaluation time — copied from
    // PolicyDecision.deductionDays, itself part of the same immutable policy snapshot as
    // policyId/policyVersion above. Null for penalties evaluated before this column existed.
    @Column(name = "deduction_days")
    private BigDecimal deductionDays;

    // ── Deduction outcome — set by PenaltyDeductionService right after this row is first
    // persisted, in the same transaction. Null for penalties evaluated before this existed. ──
    @Column(name = "deduction_method")
    private String deductionMethod;

    @Column(name = "leave_deduction_days")
    private BigDecimal leaveDeductionDays;

    @Column(name = "lop_days")
    private BigDecimal lopDays;

    // JSON snapshot of {leaveTypeCode: daysDeducted}, e.g. {"SICK":1,"CASUAL":0.5} — traceability
    // for which specific leave types absorbed the deduction (Section 39).
    @Column(name = "leave_breakdown", columnDefinition = "TEXT")
    private String leaveBreakdown;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    // When this row was created (i.e. when the penalty was applied against the employee).
    @Column(name = "penalized_on", nullable = false)
    private LocalDateTime penalizedOn;

    // ── Cancellation metadata — set only when status transitions to CANCELLED ──
    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

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
