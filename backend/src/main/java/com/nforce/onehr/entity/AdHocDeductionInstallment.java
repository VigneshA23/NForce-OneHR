package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C25 — one cycle's worth of recovery against an {@link AdHocDeduction} plan, applied to one
 * {@link PayrollRun}. Unique on {@code (adHocDeduction, payrollRun)} (see V262) — that's what
 * makes {@code PayrollRunService#buildPayslip} idempotent under regeneration: regenerating the
 * SAME DRAFT run finds the row already here and reuses its {@link #amount} verbatim instead of
 * decrementing the plan's balance again.
 *
 * <p>Deleted (and the plan's {@link AdHocDeduction#getRemainingBalance()} restored) by {@code
 * PayrollRunService#rollbackWholeRun} — a rolled-back run is "as if it never ran," so the
 * installment it collected has to come back too, or the employee would be short-charged on the
 * next real attempt at that cycle.
 */
@Entity
@Table(name = "ad_hoc_deduction_installments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdHocDeductionInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ad_hoc_deduction_id", nullable = false)
    private AdHocDeduction adHocDeduction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private LocalDateTime appliedAt;

    @PrePersist
    protected void onCreate() {
        appliedAt = LocalDateTime.now();
    }
}
