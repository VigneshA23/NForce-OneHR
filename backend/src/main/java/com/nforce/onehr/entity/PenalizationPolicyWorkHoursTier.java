package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the Work Hours Shortage section's tiered deduction table (e.g. "less than 90% of
 * shift hours → 0.5 day", "less than 50% of shift hours → 1 day" — the exact two tiers shown in
 * the approved screenshots). Belongs to exactly one immutable {@link PenalizationPolicyVersion};
 * never edited after that version is superseded.
 */
@Entity
@Table(name = "penalization_policy_work_hours_tiers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenalizationPolicyWorkHoursTier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "policy_version_id", nullable = false)
    private UUID policyVersionId;

    // "Less than X% of shift hours" — the tier's upper bound.
    @Column(name = "threshold_percent", nullable = false)
    private BigDecimal thresholdPercent;

    @Column(name = "deduction_days", nullable = false)
    private BigDecimal deductionDays;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
