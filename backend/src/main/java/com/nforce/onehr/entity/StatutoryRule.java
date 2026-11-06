package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C13 (ONEHR-619, Phase 2) — config/versioning only, deliberately not live computation yet:
 * the Implementation Plan's own scoping is "getting the versioning right now means Phase 3's
 * payslip math has real rules to read instead of hardcoded placeholders." No code here reads or
 * applies {@code slabConfig} to compute a deduction — that's gated on the jurisdiction decision
 * (Decision #7, India-only vs. multi-country) and Decision #5 (PF/ESI/etc. — SalaryComponent vs.
 * StatutoryRule, pick one per deduction type).
 *
 * <p>Effective-dated the same way as {@link EmployeeCompensation}/{@link EmployeeManagerHistory}:
 * effectiveTo IS NULL marks the current rule for a given (country, ruleType); a revision is an
 * INSERT + one UPDATE to close the prior row, never an UPDATE in place — so a payslip for a past
 * period can still read the rule that was actually in effect for it.
 *
 * <p>{@code ruleType} is a free-text label ("PF", "ESI", "PT", "TDS", ...), not an enum — which
 * statutory deductions exist, and their slab shapes, isn't decided yet (jurisdiction-dependent).
 * {@code slabConfig} is opaque jsonb for the same reason: its shape is a Phase-3/jurisdiction
 * concern, this story only has to store and version whatever gets put in it.
 */
@Entity
@Table(name = "statutory_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StatutoryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 10)
    private String country;

    @Column(name = "rule_type", nullable = false, length = 30)
    private String ruleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "slab_config", columnDefinition = "jsonb", nullable = false)
    private String slabConfig;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
