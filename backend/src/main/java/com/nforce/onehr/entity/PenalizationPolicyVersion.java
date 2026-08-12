package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One immutable, versioned snapshot of the org's Penalization Policy — the Organization Masters
 * "Penalization Policy" tab configures rows of this entity. {@code policyId} stays constant
 * across every version of this one document; {@code version} increments per save. This is the
 * same point-in-time-snapshot contract {@link AttendancePenalty#getPolicyId()}/
 * {@link AttendancePenalty#getPolicyVersion()} already expect — saving a new version never
 * mutates an older one, so a historical {@link AttendancePenalty} keeps referring to the exact
 * configuration that produced it.
 *
 * <p>Deliberately unrelated to {@link PenalisationPolicy} (V95) — that entity is an
 * employee-assignment label (name/description only), not attendance-penalty configuration.
 *
 * <p>Four independently enable-able sections, matching the approved screenshots: No Attendance,
 * Late Arrival, Work Hours Shortage, Missing Logs. Fields fall into two kinds:
 * <ul>
 *   <li><b>Gate fields</b> (grace periods, exempt counts, thresholds) — read by
 *       {@link com.nforce.onehr.service.ConfiguredAttendancePolicyEngine} to decide
 *       NO_MATCH/EXEMPT/APPLY_PENALTY.</li>
 *   <li><b>Amount fields</b> ({@code *_deduction_days}, {@code *_deduction_per_shifts},
 *       {@code ml_deduction_mode}) — configure how much a matched occurrence should deduct.
 *       {@link AttendancePenalty} has no deduction-amount column (there is no payroll consumer in
 *       this system), so these are stored/versioned/displayed but not read by the engine — that is
 *       a structural fact about what a policy *decision* is for, not an oversight.</li>
 * </ul>
 */
@Entity
@Table(name = "penalization_policy_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenalizationPolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    // ── No Attendance ──
    @Column(name = "no_attendance_enabled", nullable = false)
    @Builder.Default
    private boolean noAttendanceEnabled = false;
    @Column(name = "na_deduction_days")
    private BigDecimal naDeductionDays;
    @Column(name = "na_no_show_enabled", nullable = false)
    @Builder.Default
    private boolean naNoShowEnabled = false;
    @Column(name = "na_no_show_threshold_hours")
    private BigDecimal naNoShowThresholdHours;

    // ── Late Arrival ──
    @Column(name = "late_arrival_enabled", nullable = false)
    @Builder.Default
    private boolean lateArrivalEnabled = false;
    @Column(name = "la_basis")
    private String laBasis;
    @Column(name = "la_grace_period_minutes")
    private Integer laGracePeriodMinutes;
    @Column(name = "la_exempt_count")
    private Integer laExemptCount;
    @Column(name = "la_exempt_period")
    private String laExemptPeriod;
    @Column(name = "la_deduction_days")
    private BigDecimal laDeductionDays;
    @Column(name = "la_deduction_per_shifts")
    private Integer laDeductionPerShifts;
    @Column(name = "la_ignore_when_effective_hours_met_enabled", nullable = false)
    @Builder.Default
    private boolean laIgnoreWhenEffectiveHoursMetEnabled = false;

    // ── Work Hours Shortage ──
    @Column(name = "work_hours_shortage_enabled", nullable = false)
    @Builder.Default
    private boolean workHoursShortageEnabled = false;
    @Column(name = "whs_deduction_basis")
    private String whsDeductionBasis;
    @Column(name = "whs_deduction_period")
    private String whsDeductionPeriod;
    @Column(name = "whs_apply_penalty_for_shortage_enabled", nullable = false)
    @Builder.Default
    private boolean whsApplyPenaltyForShortageEnabled = true;
    @Column(name = "whs_apply_penalty_for_late_arrival_enabled", nullable = false)
    @Builder.Default
    private boolean whsApplyPenaltyForLateArrivalEnabled = false;

    // ── Missing Logs ──
    @Column(name = "missing_logs_enabled", nullable = false)
    @Builder.Default
    private boolean missingLogsEnabled = false;
    @Column(name = "ml_exempt_days")
    private Integer mlExemptDays;
    @Column(name = "ml_exempt_period")
    private String mlExemptPeriod;
    @Column(name = "ml_deduction_mode")
    private String mlDeductionMode;
    @Column(name = "ml_deduction_days")
    private BigDecimal mlDeductionDays;
    @Column(name = "ml_deduction_per_shifts")
    private Integer mlDeductionPerShifts;
    @Column(name = "ml_ignore_rule_enabled", nullable = false)
    @Builder.Default
    private boolean mlIgnoreRuleEnabled = false;
    @Column(name = "ml_ignore_rule_threshold_percent")
    private BigDecimal mlIgnoreRuleThresholdPercent;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
