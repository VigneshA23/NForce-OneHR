package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Keka's "Arrears" step — read-only in the UI, confirmed by the SME as auto-computed from three
 * triggers, never manually entered: a backdated {@link EmployeeCompensation} revision affecting an
 * already-closed cycle, a {@link PayrollLopCalculation} override on a past cycle ("LOP Reversal"),
 * or releasing a PROCESSING-type {@link SalaryHold} after its run has already moved past DRAFT.
 * Each trigger computes a signed delta (positive = owed to the employee, negative = a recovery)
 * against that cycle's already-persisted {@link Payslip} — the historical payslip itself is never
 * rewritten; this row is the correction, applied later as its own line item.
 *
 * <p>{@code status}: {@code PENDING} until a later {@link PayrollRunService#buildPayslip} call for
 * this employee picks it up and turns it into a {@link PayslipLineItem} ({@code "Arrears"} earning
 * if positive, {@code "Arrears Recovery"} deduction if negative — same silent-skip-if-no-catalog-
 * component convention {@link com.nforce.onehr.service.StatutoryDeductionService} already uses),
 * at which point it flips to {@code APPLIED} with {@code appliedToRunId} set — idempotent on a
 * same-run regenerate for the same reason ad-hoc deduction installments already are: "already
 * APPLIED to this exact run" short-circuits re-creating the line item a second time.
 */
@Entity
@Table(name = "arrears")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Arrears {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "pay_group_id", nullable = false)
    private UUID payGroupId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    // SALARY_REVISION | LOP_REVERSAL | HOLD_RELEASE — see ArrearsSourceType.
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_cycle_start", nullable = false)
    private LocalDate sourceCycleStart;

    @Column(name = "source_cycle_end", nullable = false)
    private LocalDate sourceCycleEnd;

    // PENDING | APPLIED — see ArrearsStatus.
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = ArrearsStatus.PENDING;

    @Column(name = "applied_to_run_id")
    private UUID appliedToRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
