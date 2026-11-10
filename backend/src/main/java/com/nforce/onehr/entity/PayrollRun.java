package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C08 (ONEHR-614), Phase 3 — one payroll batch for one {@link PayGroup} cycle. The hub ten
 * other stories depend on; see {@link com.nforce.onehr.service.PayrollRunService} for the
 * generation/computation engine and every formula assumption it documents.
 *
 * <p>Status model incorporates US-C27 from the start (not retrofitted): the original AC
 * ("immutable once finalized, changes need a new arrears run") is superseded by "no silent
 * edits" — APPROVED/DISBURSED can only change via {@link
 * com.nforce.onehr.service.PayrollRunService#rollbackWholeRun}, which is itself reasoned and
 * audited, reverting status back to DRAFT for reprocessing. There is still no direct
 * UPDATE-in-place path for a locked run.
 *
 * <p><b>Scope note:</b> per-employee selective rollback (US-C27's other AC) is NOT implemented in
 * this increment — only whole-run rollback.
 *
 * <p><b>US-C09 (ONEHR-579):</b> {@code approve} now runs through the same {@code ApprovalRule}
 * chain EXPENSE uses — {@link #approvalStages} and {@link #pendingApprovalStage} are snapshotted
 * at {@code submit} time (parallel to {@code ExpenseClaim}'s own stage snapshot) and advanced by
 * {@link com.nforce.onehr.service.PayrollRunService#approve} one stage at a time. A run can also
 * be rejected back to DRAFT while SUBMITTED (not just APPROVED/DISBURSED) via {@link
 * com.nforce.onehr.service.PayrollRunService#rollbackWholeRun} — see that method's Javadoc for why
 * that guard was widened.
 */
@Entity
@Table(name = "payroll_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayrollRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "pay_group_id", nullable = false)
    private UUID payGroupId;

    @Column(name = "cycle_start", nullable = false)
    private LocalDate cycleStart;

    @Column(name = "cycle_end", nullable = false)
    private LocalDate cycleEnd;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = PayrollRunStatus.DRAFT;

    @Column(name = "employee_count", nullable = false)
    private int employeeCount;

    @Column(name = "total_gross", nullable = false)
    private BigDecimal totalGross;

    @Column(name = "total_deductions", nullable = false)
    private BigDecimal totalDeductions;

    @Column(name = "total_net", nullable = false)
    private BigDecimal totalNet;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "disbursed_at")
    private LocalDateTime disbursedAt;

    @Column(name = "last_rolled_back_at")
    private LocalDateTime lastRolledBackAt;

    @Column(name = "last_rollback_reason", columnDefinition = "TEXT")
    private String lastRollbackReason;

    // US-C09 — snapshotted at submit() time from whichever ApprovalRule is active for PAYROLL_RUN,
    // same convention as ExpenseClaim.approvalStages: comma-separated role codes, e.g.
    // "HR_ADMIN,SUPER_ADMIN". Null for a run submitted before this story (no chain to walk —
    // PayrollRunService#approve treats that the same as a single HR_ADMIN-only stage).
    @Column(name = "approval_stages")
    private String approvalStages;

    // The stage currently awaiting action, e.g. "HR_ADMIN" then "SUPER_ADMIN" for a two-stage
    // chain. Null once APPROVED (or before submission). Status stays SUBMITTED across every
    // intermediate stage — only this field advances — same shape as ExpenseClaim.pendingFinalStage.
    @Column(name = "pending_approval_stage", length = 20)
    private String pendingApprovalStage;

    // Which ApprovalRule row (if any) was evaluated at submit time — null means the legacy
    // single-stage default applied (no active rule for PAYROLL_RUN).
    @Column(name = "evaluated_rule_id")
    private UUID evaluatedRuleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
