package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C24 (ONEHR-579) — a one-off payment (bonus, OT, referral, relocation, ...) added to a single
 * employee's payslip within one {@link PayrollRun}, on top of their recurring {@link
 * com.nforce.onehr.entity.SalaryStructureComponent} rows. Scoped to catalog entries of type {@code
 * AD_HOC_EARNING} only — {@code AD_HOC_DEDUCTION} (with installment/balance tracking) is US-C25's
 * own, more complex model, not this one.
 *
 * <p>Tied to the {@link PayrollRun} row itself, not a looser (employee, cycle) pairing — a DRAFT
 * run's id is stable across regeneration (only its payslips/line items are deleted and rebuilt,
 * see {@code PayrollRunService#generateDraft}), so an ad-hoc payment added before a regenerate is
 * picked back up automatically by {@code PayrollRunService#buildPayslip} on the next build.
 *
 * <p>No separate "processed" flag: mutating an ad-hoc payment is gated by the run's own status
 * (DRAFT only, same guard {@code generateDraft}'s regenerate path already uses) rather than
 * duplicating that state here — once SUBMITTED, the row is already permanent history behind
 * whatever {@link PayslipLineItem} it produced, same as every other component on that payslip.
 */
@Entity
@Table(name = "ad_hoc_payments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdHocPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    // Snapshotted (not read live from the catalog at payslip-build time) — same convention as
    // PayslipLineItem, so a later catalog rename/deactivation never changes what an already-added
    // ad-hoc payment says it was for.
    @Column(name = "component_id", nullable = false)
    private UUID componentId;

    @Column(name = "component_name", nullable = false, length = 50)
    private String componentName;

    // Always AD_HOC_EARNING today (see class Javadoc) — stored rather than hardcoded so a future
    // relaxation of scope doesn't need a schema change.
    @Column(name = "component_type", nullable = false, length = 20)
    private String componentType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "added_by", nullable = false)
    private UUID addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    @PrePersist
    protected void onCreate() {
        addedAt = LocalDateTime.now();
    }
}
