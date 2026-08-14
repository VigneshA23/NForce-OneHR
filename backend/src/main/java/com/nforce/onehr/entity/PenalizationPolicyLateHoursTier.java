package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the Late Arrival section's "Total Late Hours in Shift" tiered deduction table
 * (Section 31) — "greater than X hours -> Y day(s)". Same shape/contract as
 * {@link PenalizationPolicyWorkHoursTier}: belongs to exactly one immutable
 * {@link PenalizationPolicyVersion}, never edited after that version is superseded.
 */
@Entity
@Table(name = "penalization_policy_late_hours_tiers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenalizationPolicyLateHoursTier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "policy_version_id", nullable = false)
    private UUID policyVersionId;

    // "Greater than X hours" — the tier's lower bound.
    @Column(name = "threshold_hours", nullable = false)
    private BigDecimal thresholdHours;

    @Column(name = "deduction_days", nullable = false)
    private BigDecimal deductionDays;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
