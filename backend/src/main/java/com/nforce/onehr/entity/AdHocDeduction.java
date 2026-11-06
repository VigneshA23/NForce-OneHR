package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C25 (ONEHR-579) — a recovery plan for an employee (asset damage, an overpayment, ...),
 * recovered in installments across however many payroll cycles it takes, rather than in one shot
 * like {@link AdHocPayment}. Scoped to catalog entries of type {@code AD_HOC_DEDUCTION} only.
 *
 * <p>Unlike {@code AdHocPayment}, this is NOT tied to a single {@link PayrollRun} — it's created
 * once and {@code PayrollRunService#buildPayslip} applies one {@link AdHocDeductionInstallment}
 * per run it sees this employee in, for as long as {@link #remainingBalance} is above zero. See
 * {@link AdHocDeductionInstallment}'s own Javadoc for how regeneration and rollback stay correct.
 */
@Entity
@Table(name = "ad_hoc_deductions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdHocDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    // Snapshotted (not read live from the catalog) — same convention as AdHocPayment.
    @Column(name = "component_id", nullable = false)
    private UUID componentId;

    @Column(name = "component_name", nullable = false, length = 50)
    private String componentName;

    // Always AD_HOC_DEDUCTION today (see class Javadoc) — stored rather than hardcoded so a
    // future relaxation of scope doesn't need a schema change.
    @Column(name = "component_type", nullable = false, length = 20)
    private String componentType;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    // The amount recovered per cycle — the LAST installment may be smaller (whatever's left of
    // remainingBalance), never larger.
    @Column(name = "installment_amount", nullable = false)
    private BigDecimal installmentAmount;

    @Column(name = "remaining_balance", nullable = false)
    private BigDecimal remainingBalance;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = AdHocDeductionStatus.ACTIVE;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
