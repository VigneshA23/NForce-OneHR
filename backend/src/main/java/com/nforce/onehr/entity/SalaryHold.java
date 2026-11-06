package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C26 (ONEHR-579) — "Hold processing" (Keka's own term for this, confirmed against its help
 * docs 2026-11-06): an employee excluded ENTIRELY from a DRAFT run's payslip generation for this
 * one cycle — no payslip, no line items, zero statutory deductions, same as if they weren't
 * effective at all that cycle. Keka's docs name a second, distinct mechanism, "Hold payout" (the
 * employee IS processed normally and statutory still applies, only the actual payment is
 * withheld) — that needs per-payslip disbursement tracking this codebase doesn't have yet
 * ({@code PayrollRunService#disburse} is still whole-run-only), so it's NOT built here; see that
 * class's own Javadoc for the explicit scope note.
 *
 * <p>Tied to the {@link PayrollRun} row itself, same reasoning as {@link AdHocPayment}: created
 * against an already-existing (DRAFT) run, and {@code PayrollRunService#generateDraft} only
 * excludes the held employee from the NEXT regenerate — creating the hold doesn't retroactively
 * remove an already-built payslip by itself.
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
