package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The one production {@link AttendancePolicyEngine} implementation. Reads the version effective
 * for {@code context.getAttendanceDate()} straight from {@link PenalizationPolicyVersionRepository}
 * on every call — no caching, no synchronization mechanism — so a Penalization Policy save in
 * Organization Masters is visible to the very next evaluation without a restart or deployment.
 *
 * <p>Every field below is either a <b>gate</b> (read here, decides NO_MATCH/EXEMPT/APPLY_PENALTY)
 * or an <b>amount</b> field (deduction days) — every {@code APPLY_PENALTY} decision carries the
 * matched rule's configured {@code deductionDays} on {@link PolicyDecision#getDeductionDays()},
 * which {@link AttendancePenaltyEvaluationService} copies onto
 * {@link com.nforce.onehr.entity.AttendancePenalty#getDeductionDays()} — a real, persisted
 * execution path, not stored-but-unused configuration. {@code deductionPerShifts}/
 * {@code deductionMode} remain rate/aggregation *descriptions* of that same amount (every
 * approved-screenshot example uses "per 1 shift", so no multi-shift batching is implemented — see
 * {@link PolicyDecision} class javadoc). No field is stored and silently ignored by this engine.
 *
 * <p>Two configured sub-rules are deliberately NOT implemented and are excluded from the
 * persisted schema entirely (not shipped-but-inert): No Attendance's adjoining-holiday/
 * adjoining-week-off sandwich rules (they require a new multi-day look-around detection algorithm
 * that exists nowhere in this codebase, not even as a stub — building it would mean inventing
 * exception-detection logic, not exposing an existing fact) and Late Arrival's "penalise if total
 * late hours are exceeded" / "apply penalty for late arrival caused by missing logs" checkboxes
 * (the first has no companion threshold number anywhere in the approved screenshots to compare
 * against; the second needs a "was this lateness caused by a missing log" fact that doesn't exist
 * and isn't cheaply derivable from {@code Attendance}).
 */
@Service
@RequiredArgsConstructor
public class ConfiguredAttendancePolicyEngine implements AttendancePolicyEngine {

    private final PenalizationPolicyVersionRepository versionRepository;
    private final PenalizationPolicyWorkHoursTierRepository tierRepository;

    @Override
    public PolicyDecision evaluate(PolicyEvaluationContext context) {
        Optional<PenalizationPolicyVersion> effective = versionRepository
                .findVersionsEffectiveAt(context.getAttendanceDate().atStartOfDay())
                .stream().findFirst();
        if (effective.isEmpty()) {
            return noMatch(null, null, "No Penalization Policy version is effective for this date.");
        }
        PenalizationPolicyVersion version = effective.get();

        if (context.getDiscrepancyType() == null) {
            return noMatch(version.getPolicyId(), version.getVersion(), "No discrepancy type given.");
        }
        return switch (context.getDiscrepancyType()) {
            case ExceptionType.NO_ATTENDANCE -> evaluateNoAttendance(version, context);
            case ExceptionType.LATE_ARRIVAL -> evaluateLateArrival(version, context);
            case ExceptionType.WORK_HOURS_SHORTAGE -> evaluateWorkHoursShortage(version, context);
            case ExceptionType.MISSING_PUNCH -> evaluateMissingLogs(version, context);
            default -> noMatch(version.getPolicyId(), version.getVersion(),
                    "No configured Penalization Policy section covers this discrepancy type.");
        };
    }

    private PolicyDecision evaluateNoAttendance(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (!v.isNoAttendanceEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "No Attendance section is disabled.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }
        return applyPenalty(v, v.getNaDeductionDays(), "No attendance was recorded for this working day.");
    }

    private PolicyDecision evaluateLateArrival(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (!v.isLateArrivalEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Late Arrival section is disabled.");
        }
        if (ctx.getLateMinutes() == null) {
            return configurationRequired(v, "lateMinutes fact is required to evaluate Late Arrival.");
        }
        if (v.getLaGracePeriodMinutes() != null && ctx.getLateMinutes() <= v.getLaGracePeriodMinutes()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Late minutes are within the configured grace period.");
        }
        if (v.isLaIgnoreWhenEffectiveHoursMetEnabled()
                && ctx.getEffectiveHoursPercent() != null && ctx.getEffectiveHoursPercent() >= 100.0) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Employee completed full effective hours despite late arrival.");
        }
        // "Exempt N late arrival(s) in a Month" — lateArrivalCountInPeriod is this occurrence's
        // running count for the period, inclusive of itself (see PolicyEvaluationContext javadoc);
        // "Post N late arrivals, deduct..." means the (N+1)th occurrence is the first one penalized.
        if (v.getLaExemptCount() != null && ctx.getLateArrivalCountInPeriod() != null
                && ctx.getLateArrivalCountInPeriod() <= v.getLaExemptCount()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Late arrival occurrence is within the exempt count for this period.");
        }
        if (ctx.isWorkHoursShortageAlsoOccurredSameDay() && v.isWorkHoursShortageEnabled()
                && !v.isWhsApplyPenaltyForLateArrivalEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(),
                    "Work Hours Shortage also occurred the same day and Late Arrival penalty is suppressed by configuration.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }
        return applyPenalty(v, v.getLaDeductionDays(), "Late minutes exceed the configured grace period.");
    }

    private PolicyDecision evaluateWorkHoursShortage(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        // No Attendance's no-show-by-hours rule reclassifies a sufficiently-low-hours day as a
        // no-attendance occurrence rather than a shortage tier — evaluated first since it changes
        // which section's gate applies, not just which reason string is returned.
        if (v.isNoAttendanceEnabled() && v.isNaNoShowEnabled()
                && ctx.getWorkedMinutes() != null && v.getNaNoShowThresholdHours() != null
                && ctx.getWorkedMinutes() < v.getNaNoShowThresholdHours().doubleValue() * 60) {
            if (isRegularized(ctx)) {
                return exempt(v, "A pending or approved regularization covers this date.");
            }
            return applyPenalty(v, v.getNaDeductionDays(), "Worked hours are below the configured no-show threshold — treated as no attendance.");
        }

        if (!v.isWorkHoursShortageEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Work Hours Shortage section is disabled.");
        }
        if (ctx.getEffectiveHoursPercent() == null) {
            return configurationRequired(v, "effectiveHoursPercent fact is required to evaluate Work Hours Shortage.");
        }
        if (ctx.isLateArrivalAlsoOccurredSameDay() && !v.isWhsApplyPenaltyForShortageEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(),
                    "Late Arrival also occurred the same day and Work Hours Shortage penalty is suppressed by configuration.");
        }

        List<PenalizationPolicyWorkHoursTier> tiers = tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(v.getId());
        Optional<PenalizationPolicyWorkHoursTier> matched = tiers.stream()
                .filter(t -> ctx.getEffectiveHoursPercent() < t.getThresholdPercent().doubleValue())
                // Most severe matching tier (lowest threshold) — an employee below 50% also
                // qualifies for the "less than 90%" tier but the stricter one governs.
                .min(Comparator.comparing(PenalizationPolicyWorkHoursTier::getThresholdPercent));
        if (matched.isEmpty()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Effective hours percent does not fall below any configured tier.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }
        // The matched tier's own deduction, not a version-level field — a "less than 50%" match
        // deducts that tier's amount, not the "less than 90%" tier's.
        return applyPenalty(v, matched.get().getDeductionDays(), "Effective hours percent is below a configured shortage tier.");
    }

    private PolicyDecision evaluateMissingLogs(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (!v.isMissingLogsEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Missing Logs section is disabled.");
        }
        if (v.isMlIgnoreRuleEnabled() && ctx.getEffectiveHoursPercent() != null && v.getMlIgnoreRuleThresholdPercent() != null
                && ctx.getEffectiveHoursPercent() > v.getMlIgnoreRuleThresholdPercent().doubleValue()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Effective hours percent exceeds the configured ignore-rule threshold.");
        }
        if (v.getMlExemptDays() != null && ctx.getMissingLogCountInPeriod() != null
                && ctx.getMissingLogCountInPeriod() <= v.getMlExemptDays()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Missing-log occurrence is within the exempt days for this period.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }
        return applyPenalty(v, v.getMlDeductionDays(), "Missing-log occurrences exceed the configured exempt days for this period.");
    }

    private boolean isRegularized(PolicyEvaluationContext ctx) {
        return ctx.isHasPendingRegularization() || ctx.isHasApprovedRegularization();
    }

    private PolicyDecision noMatch(java.util.UUID policyId, Integer version, String reason) {
        return PolicyDecision.builder().type(PolicyDecisionType.NO_MATCH)
                .policyId(policyId).policyVersion(version).reason(reason).build();
    }

    private PolicyDecision exempt(PenalizationPolicyVersion v, String reason) {
        return PolicyDecision.builder().type(PolicyDecisionType.EXEMPT)
                .policyId(v.getPolicyId()).policyVersion(v.getVersion()).reason(reason).build();
    }

    private PolicyDecision applyPenalty(PenalizationPolicyVersion v, java.math.BigDecimal deductionDays, String reason) {
        return PolicyDecision.builder().type(PolicyDecisionType.APPLY_PENALTY)
                .policyId(v.getPolicyId()).policyVersion(v.getVersion()).deductionDays(deductionDays).reason(reason).build();
    }

    private PolicyDecision configurationRequired(PenalizationPolicyVersion v, String reason) {
        return PolicyDecision.builder().type(PolicyDecisionType.CONFIGURATION_REQUIRED)
                .policyId(v.getPolicyId()).policyVersion(v.getVersion()).reason(reason).build();
    }
}
