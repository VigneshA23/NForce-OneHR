package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C08 — one employee's payslip within one {@link PayrollRun}. Header totals (gross/deductions/
 * net) are derived from this employee's {@link PayslipLineItem} rows at generation time and stored
 * here for fast reads and for US-C10's reconciliation check.
 *
 * <p>{@code released} gates employee-facing visibility (US-C12, not built yet, will read this) —
 * deliberately separate from {@link PayrollRun#getStatus()} so a per-employee rollback (US-C27,
 * {@code PayrollRunService#rollbackEmployeePayslip}) can withdraw one payslip without needing a
 * different run-level status model.
 *
 * <p>{@code paymentWithheld} is US-C26's "Hold payout" (see {@link SalaryHold}'s own Javadoc):
 * set by {@code PayrollRunService#disburse} for whichever employee has an ACTIVE {@link
 * SalaryHoldType#PAYOUT} hold on this run at that moment, rather than excluding them from this
 * payslip's own generation the way {@link SalaryHoldType#PROCESSING} does.
 */
@Entity
@Table(name = "payslips")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payslip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(nullable = false)
    private BigDecimal ctc;

    @Column(name = "cycle_days", nullable = false)
    private int cycleDays;

    @Column(name = "lop_days", nullable = false)
    private BigDecimal lopDays;

    @Column(name = "employed_days", nullable = false)
    private int employedDays;

    // (employedDays - lopDays) / cycleDays, clamped at 0 — see PayrollRunService for why LOP and
    // joiner/exit proration are combined this way rather than multiplied as separate fractions.
    @Column(name = "proration_factor", nullable = false)
    private BigDecimal prorationFactor;

    @Column(name = "gross_pay", nullable = false)
    private BigDecimal grossPay;

    @Column(name = "total_deductions", nullable = false)
    private BigDecimal totalDeductions;

    @Column(name = "net_pay", nullable = false)
    private BigDecimal netPay;

    @Column(nullable = false)
    @Builder.Default
    private boolean released = false;

    @Column(name = "payment_withheld", nullable = false)
    @Builder.Default
    private boolean paymentWithheld = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
