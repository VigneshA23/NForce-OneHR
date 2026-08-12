package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * The real {@link AttendancePolicyEngine} implementation. The
 * {@link #gracePeriodChange_changesEvaluationWithoutAnyCodeChange} test is the mandated critical
 * acceptance test: it proves Organization Masters configuration — not Java code — decides the
 * outcome for the identical attendance fact.
 */
@ExtendWith(MockitoExtension.class)
class ConfiguredAttendancePolicyEngineTest {

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;

    private ConfiguredAttendancePolicyEngine engine;

    private final UUID policyId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 8, 15);

    private ConfiguredAttendancePolicyEngine newEngine() {
        return new ConfiguredAttendancePolicyEngine(versionRepository, tierRepository);
    }

    private PenalizationPolicyVersion.PenalizationPolicyVersionBuilder baseVersion(int version) {
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(version)
                .effectiveFrom(LocalDateTime.of(2026, 8, 1, 0, 0));
    }

    private PolicyEvaluationContext.PolicyEvaluationContextBuilder baseContext(String discrepancyType) {
        return PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(date).discrepancyType(discrepancyType);
    }

    // ── 1. No policy ──
    @Test
    void noConfiguredPolicy_returnsNoMatch() {
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of());
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(12).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
        assertNull(decision.getPolicyId());
        assertNull(decision.getPolicyVersion());
    }

    // ── 2. Disabled section ──
    @Test
    void disabledSection_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1).lateArrivalEnabled(false).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(30).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── 3. Outside effective period ──
    @Test
    void outsideEffectivePeriod_returnsNoMatch() {
        // The repository query itself is what enforces "outside effective period" — simulate the
        // no-row-matches outcome a real query would give for a date before any version started.
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of());
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(30).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── 4 & 5. Matching late-arrival policy / grace period prevents penalty ──
    @Test
    void lateMinutesBeyondGrace_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laDeductionDays(new BigDecimal("0.5")).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(12).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        assertEquals(policyId, decision.getPolicyId());
        assertEquals(1, decision.getPolicyVersion());
        assertEquals(new BigDecimal("0.5"), decision.getDeductionDays());
    }

    @Test
    void lateMinutesWithinGrace_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(15).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(12).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── MANDATORY CRITICAL ACCEPTANCE TEST ──
    // Organization Masters -> persisted policy -> policy version -> policy engine -> attendance
    // facts -> policy decision. No engine code changes between the two evaluations below.
    @Test
    void gracePeriodChange_changesEvaluationWithoutAnyCodeChange() {
        int lateMinutes = 12;
        PolicyEvaluationContext sameFactEveryTime = baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(lateMinutes).build();
        engine = newEngine();

        // V1: grace = 10 minutes. 12 > 10 -> APPLY_PENALTY.
        PenalizationPolicyVersion v1 = baseVersion(1).lateArrivalEnabled(true).laGracePeriodMinutes(10).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(v1));
        PolicyDecision decisionUnderV1 = engine.evaluate(sameFactEveryTime);
        assertEquals(PolicyDecisionType.APPLY_PENALTY, decisionUnderV1.getType());
        assertEquals(1, decisionUnderV1.getPolicyVersion());

        // HR saves V2 in Organization Masters: grace = 15 minutes. No engine/attendance code changes.
        PenalizationPolicyVersion v2 = baseVersion(2).lateArrivalEnabled(true).laGracePeriodMinutes(15).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(v2));

        // Same attendance fact (lateMinutes = 12), evaluated again through the same engine instance.
        PolicyDecision decisionUnderV2 = engine.evaluate(sameFactEveryTime);
        assertEquals(PolicyDecisionType.NO_MATCH, decisionUnderV2.getType());
        assertEquals(2, decisionUnderV2.getPolicyVersion());

        // V1's own decision object is untouched by V2 existing — proves no shared mutable state.
        assertEquals(PolicyDecisionType.APPLY_PENALTY, decisionUnderV1.getType());
        assertEquals(1, decisionUnderV1.getPolicyVersion());
    }

    // ── 6 (restated). Non-matching rule: exempt count absorbs the occurrence ──
    @Test
    void withinExemptCount_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(2).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    @Test
    void beyondExemptCount_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(3).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    // ── 7. Work-hours shortage threshold affects evaluation ──
    @Test
    void effectiveHoursBelowTier_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1).workHoursShortageEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("90")).deductionDays(new BigDecimal("0.5")).sortOrder(0).build(),
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("50")).deductionDays(BigDecimal.ONE).sortOrder(1).build()
        ));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.WORK_HOURS_SHORTAGE)
                .effectiveHoursPercent(82.0).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        // 82% falls below the "less than 90%" tier only, not "less than 50%" — its own deduction, 0.5, applies.
        assertEquals(new BigDecimal("0.5"), decision.getDeductionDays());
    }

    @Test
    void effectiveHoursBelowBothTiers_usesMostSevereTiersDeduction() {
        PenalizationPolicyVersion version = baseVersion(1).workHoursShortageEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("90")).deductionDays(new BigDecimal("0.5")).sortOrder(0).build(),
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("50")).deductionDays(BigDecimal.ONE).sortOrder(1).build()
        ));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.WORK_HOURS_SHORTAGE)
                .effectiveHoursPercent(30.0).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        // 30% falls below both tiers — the stricter "less than 50%" tier's own deduction (1 day) governs.
        assertEquals(BigDecimal.ONE, decision.getDeductionDays());
    }

    @Test
    void effectiveHoursAboveEveryTier_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1).workHoursShortageEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("90")).deductionDays(new BigDecimal("0.5")).sortOrder(0).build()
        ));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.WORK_HOURS_SHORTAGE)
                .effectiveHoursPercent(95.0).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── 8. Missing-log configuration affects evaluation ──
    @Test
    void missingLogsBeyondExemptDays_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1).missingLogsEnabled(true).mlExemptDays(5).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH)
                .missingLogCountInPeriod(6).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    @Test
    void missingLogsWithinExemptDays_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1).missingLogsEnabled(true).mlExemptDays(5).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH)
                .missingLogCountInPeriod(3).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── 9. No-attendance configuration affects evaluation ──
    @Test
    void noAttendanceEnabled_appliesPenaltyPerOccurrence() {
        PenalizationPolicyVersion version = baseVersion(1).noAttendanceEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.NO_ATTENDANCE).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    // ── Supported exemption: approved regularization -> EXEMPT ──
    @Test
    void approvedRegularization_returnsExempt() {
        PenalizationPolicyVersion version = baseVersion(1).noAttendanceEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.NO_ATTENDANCE)
                .hasApprovedRegularization(true).build());

        assertEquals(PolicyDecisionType.EXEMPT, decision.getType());
        assertEquals(1, decision.getPolicyVersion());
    }

    // ── Same-day interaction: Work Hours Shortage suppresses Late Arrival penalty when configured ──
    @Test
    void sameDayShortage_suppressesLateArrivalPenaltyWhenConfigured() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10)
                .workHoursShortageEnabled(true).whsApplyPenaltyForLateArrivalEnabled(false)
                .build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).workHoursShortageAlsoOccurredSameDay(true).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }
}
