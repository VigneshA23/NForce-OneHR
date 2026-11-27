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

    // ── LV-02: limits enforced on submit and approve (null = no limit) ──

    public static final String MODE_QUOTA = "QUOTA";
    public static final String MODE_UNLIMITED = "UNLIMITED";
    public static final String MODE_EARNED_ONLY = "EARNED_ONLY";

    /** QUOTA (days), UNLIMITED (nothing checked or deducted) or EARNED_ONLY (no automatic credits). */
    @Column(name = "entitlement_mode", nullable = false, length = 12)
    @Builder.Default
    private String entitlementMode = MODE_QUOTA;

    @Column(name = "min_days_per_request")
    private BigDecimal minDaysPerRequest;

    @Column(name = "max_days_per_request")
    private BigDecimal maxDaysPerRequest;

    @Column(name = "max_requests_per_month")
    private Integer maxRequestsPerMonth;

    @Column(name = "max_requests_per_year")
    private Integer maxRequestsPerYear;

    @Column(name = "max_consecutive_days")
    private BigDecimal maxConsecutiveDays;

    @Column(name = "notice_days")
    private Integer noticeDays;

    /** Notice applies only to requests longer than this; null = always. */
    @Column(name = "notice_applies_over_days")
    private BigDecimal noticeAppliesOverDays;

    @Column(name = "allow_half_day", nullable = false)
    @Builder.Default
    private boolean allowHalfDay = true;

    @Column(name = "allow_hourly", nullable = false)
    @Builder.Default
    private boolean allowHourly = false;

    @Column(name = "min_hours")
    private BigDecimal minHours;

    /** An attachment is required for requests longer than this (0 = always); null = never. */
    @Column(name = "attachment_required_over_days")
    private BigDecimal attachmentRequiredOverDays;

    /** CSV of lower-case extensions, e.g. "pdf,jpg,png"; null = any. */
    @Column(name = "attachment_allowed_types", length = 200)
    private String attachmentAllowedTypes;

    @Column(name = "attachment_max_mb")
    private Integer attachmentMaxMb;
}
