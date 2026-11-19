package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C13 Phase 4 — Keka's "Statutory Override" step: let HR override ONE employee's computed
 * PF/ESI/Professional-Tax amount for ONE specific cycle. Keyed by {@code (employeeId, ruleType,
 * cycleStart, cycleEnd)} — independent of any specific {@link PayrollRun} — for the exact same
 * reason {@link PayrollLopCalculation}'s override survives regeneration: everything under {@code
 * PayrollRunService#buildPayslip} is delete-and-rebuild, so an override living on a
 * per-run-scoped row (like {@link PayslipLineItem}) would be silently wiped on the next
 * regenerate. {@link com.nforce.onehr.service.StatutoryDeductionService} reads this row first,
 * before ever consulting {@link StatutoryRule}, exactly mirroring how {@code effectiveLopDays}
 * prefers {@code overrideLopDays} over {@code computedLopDays}.
 */
@Entity
@Table(name = "statutory_deduction_overrides")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StatutoryDeductionOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(nullable = false, length = 10)
    private String country;

    @Column(name = "rule_type", nullable = false, length = 30)
    private String ruleType;

    @Column(name = "cycle_start", nullable = false)
    private LocalDate cycleStart;

    @Column(name = "cycle_end", nullable = false)
    private LocalDate cycleEnd;

    @Column(name = "override_amount", nullable = false)
    private BigDecimal overrideAmount;

    @Column(name = "override_reason", nullable = false, columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "overridden_by", nullable = false)
    private UUID overriddenBy;

    @Column(name = "overridden_at", nullable = false)
    private LocalDateTime overriddenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
