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
 * this increment — only whole-run rollback. US-C09's full multi-stage ApprovalRule chain is also
 * not integrated yet; {@link com.nforce.onehr.service.PayrollRunService#approve} is a direct
 * single-step action for now.
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
