package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * LV-01: one leave type inside one {@link LeavePlanVersion}, with its rules. A type is available to an
 * employee only if their plan's version in effect contains it (and its "applies to" rules match).
 */
@Entity
@Table(name = "leave_plan_type_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder(toBuilder = true)
public class LeavePlanTypeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    /** Days per leave year (ANNUAL) or per occurrence (PER_OCCURRENCE); null for unpaid (unlimited). */
    @Column(name = "days")
    private BigDecimal days;

    /** {@link LeaveEntitlementPolicy#YEARLY} or {@link LeaveEntitlementPolicy#MONTHLY}. */
    @Column(name = "credit_frequency", nullable = false, length = 10)
    @Builder.Default
    private String creditFrequency = LeaveEntitlementPolicy.YEARLY;

    @Column(name = "carry_forward_enabled", nullable = false)
    @Builder.Default
    private boolean carryForwardEnabled = false;

    /** Null = no cap. */
    @Column(name = "carry_forward_max_days")
    private BigDecimal carryForwardMaxDays;

    /** Null = never expires; otherwise N months into the new leave year. */
    @Column(name = "carry_forward_expiry_months")
    private Integer carryForwardExpiryMonths;

    @Column(name = "carry_forward_priority", nullable = false, length = 30)
    @Builder.Default
    private String carryForwardPriority = LeaveType.CF_FIRST;
}
