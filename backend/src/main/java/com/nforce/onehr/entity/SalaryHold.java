package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C26 (ONEHR-579) — two distinct hold mechanisms, both Keka's own terms (confirmed against its
 * help docs 2026-11-06), discriminated by {@link #getHoldType()}:
 * <ul>
 *   <li><b>{@link SalaryHoldType#PROCESSING}</b> — an employee excluded ENTIRELY from a DRAFT
 *       run's payslip generation for this one cycle: no payslip, no line items, zero statutory
 *       deductions, same as if they weren't effective at all that cycle. Only makes sense while
 *       the run is still DRAFT — it only ever affects the NEXT {@code
 *       PayrollRunService#generateDraft} regenerate, never an already-built payslip.</li>
 *   <li><b>{@link SalaryHoldType#PAYOUT}</b> — the employee IS processed normally (payslip built,
 *       statutory deductions applied); only the actual payment is withheld. Unlike PROCESSING,
 *       this is designed to outlive DRAFT — it can be placed any time before the run is actually
 *       disbursed, and {@code PayrollRunService#disburse} (still whole-run) flags that one
 *       employee's {@link Payslip#isPaymentWithheld()} instead of excluding them from anything.
 *       Releasing it after disbursement flips that flag back off — see {@code
 *       SalaryHoldService#release}.</li>
 * </ul>
 *
 * <p>Tied to the {@link PayrollRun} row itself, same reasoning as {@link AdHocPayment}: created
 * against an already-existing run. An employee can only have one ACTIVE hold (of either type) on
 * a given run at a time.
 */
@Entity
@Table(name = "salary_holds")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SalaryHold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = SalaryHoldStatus.ACTIVE;

    @Column(name = "hold_type", nullable = false, length = 20)
    @Builder.Default
    private String holdType = SalaryHoldType.PROCESSING;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
