package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/** PN-05: the Early Going and Excess Break rules as decided by the real engine. */
@ExtendWith(MockitoExtension.class)
class EarlyGoingExcessBreakEngineTest {

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;

    private ConfiguredAttendancePolicyEngine engine;
    private final UUID policyId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 8, 15);

    @BeforeEach
    void setUp() {
        engine = new ConfiguredAttendancePolicyEngine(versionRepository, tierRepository, lateHoursTierRepository);
    }

    private void policy(PenalizationPolicyVersion.PenalizationPolicyVersionBuilder b) {
        PenalizationPolicyVersion v = b.id(UUID.randomUUID()).policyId(policyId).version(1)
                .effectiveFrom(LocalDateTime.of(2026, 8, 1, 0, 0)).build();
        lenient().when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(v));
    }

    private PolicyEvaluationContext.PolicyEvaluationContextBuilder ctx(String type) {
        return PolicyEvaluationContext.builder().employeeUserId(UUID.randomUUID()).attendanceDate(date).discrepancyType(type);
    }

    private PenalizationPolicyVersion.PenalizationPolicyVersionBuilder earlyGoing(int grace, Integer exempt, Integer perShifts) {
        return PenalizationPolicyVersion.builder().earlyGoingEnabled(true).egGracePeriodMinutes(grace)
                .egExemptCount(exempt).egExemptPeriod("MONTH").egDeductionDays(new BigDecimal("0.5")).egDeductionPerShifts(perShifts);
    }

    private PenalizationPolicyVersion.PenalizationPolicyVersionBuilder excessBreak(int max, int exempt) {
        return PenalizationPolicyVersion.builder().excessBreakEnabled(true).ebMaxBreakMinutes(max)
                .ebExemptCount(exempt).ebExemptPeriod("MONTH").ebDeductionDays(new BigDecimal("0.25"));
    }

    // ── Early Going ──

    @Test
    void earlyGoing_disabled_noMatch() {
        policy(PenalizationPolicyVersion.builder().earlyGoingEnabled(false));
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).earlyMinutes(60).build()).getType());
    }

    @Test
    void earlyGoing_withinGrace_noMatch() {
        policy(earlyGoing(15, 0, 1));
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).earlyMinutes(15).earlyGoingCountInPeriod(1).build()).getType());
    }

    @Test
    void earlyGoing_withinExemptCount_noMatch_thenPenalizedPastIt() {
        policy(earlyGoing(10, 2, 1));
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).earlyMinutes(30).earlyGoingCountInPeriod(2).build()).getType());

        PolicyDecision third = engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).earlyMinutes(30).earlyGoingCountInPeriod(3).build());
        assertEquals(PolicyDecisionType.APPLY_PENALTY, third.getType());
        assertEquals(new BigDecimal("0.5"), third.getDeductionDays());
    }

    @Test
    void earlyGoing_deductionPerNOccurrences_batchesPastTheExemptCount() {
        // exempt 1, deduct every 2nd occurrence past it: counts 3 and 5 deduct, 2 and 4 do not.
        policy(earlyGoing(0, 1, 2));
        for (int count : new int[]{2, 4}) {
            assertEquals(PolicyDecisionType.NO_MATCH,
                    engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).earlyMinutes(20).earlyGoingCountInPeriod(count).build()).getType(),
                    "count " + count);
        }
        for (int count : new int[]{3, 5}) {
            assertEquals(PolicyDecisionType.APPLY_PENALTY,
                    engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).earlyMinutes(20).earlyGoingCountInPeriod(count).build()).getType(),
                    "count " + count);
        }
    }

    @Test
    void earlyGoing_approvedPartialDayLeavingEarly_exemptsTheDay() {
        policy(earlyGoing(0, 0, 1));
        PolicyDecision d = engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).earlyMinutes(120).earlyGoingCountInPeriod(5)
                .hasApprovedLeavingEarlyRequest(true).build());
        assertEquals(PolicyDecisionType.EXEMPT, d.getType());
        assertTrue(d.getReason().contains("Partial Day"));
    }

    @Test
    void earlyGoing_regularizationExempts() {
        policy(earlyGoing(0, 0, 1));
        assertEquals(PolicyDecisionType.EXEMPT,
                engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).earlyMinutes(45).earlyGoingCountInPeriod(1)
                        .hasApprovedRegularization(true).build()).getType());
    }

    @Test
    void earlyGoing_missingFact_configurationRequired() {
        policy(earlyGoing(0, 0, 1));
        assertEquals(PolicyDecisionType.CONFIGURATION_REQUIRED,
                engine.evaluate(ctx(ExceptionType.EARLY_DEPARTURE).build()).getType());
    }

    // ── Excess Break ──

    @Test
    void excessBreak_disabled_noMatch() {
        policy(PenalizationPolicyVersion.builder().excessBreakEnabled(false));
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(ctx(ExceptionType.EXCESS_BREAK).breakMinutes(200).build()).getType());
    }

    @Test
    void excessBreak_atOrUnderTheLimit_noMatch() {
        policy(excessBreak(60, 0));
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(ctx(ExceptionType.EXCESS_BREAK).breakMinutes(60).excessBreakCountInPeriod(1).build()).getType());
    }

    @Test
    void excessBreak_overTheLimit_exemptCountThenDeducts() {
        policy(excessBreak(60, 2));
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(ctx(ExceptionType.EXCESS_BREAK).breakMinutes(75).excessBreakCountInPeriod(2).build()).getType());

        PolicyDecision third = engine.evaluate(ctx(ExceptionType.EXCESS_BREAK).breakMinutes(75).excessBreakCountInPeriod(3).build());
        assertEquals(PolicyDecisionType.APPLY_PENALTY, third.getType());
        assertEquals(new BigDecimal("0.25"), third.getDeductionDays());
    }

    @Test
    void excessBreak_noMaxConfigured_configurationRequired() {
        policy(PenalizationPolicyVersion.builder().excessBreakEnabled(true).ebExemptCount(0).ebDeductionDays(BigDecimal.ONE));
        assertEquals(PolicyDecisionType.CONFIGURATION_REQUIRED,
                engine.evaluate(ctx(ExceptionType.EXCESS_BREAK).breakMinutes(90).excessBreakCountInPeriod(1).build()).getType());
    }

    @Test
    void excessBreak_regularizationExempts() {
        policy(excessBreak(30, 0));
        assertEquals(PolicyDecisionType.EXEMPT,
                engine.evaluate(ctx(ExceptionType.EXCESS_BREAK).breakMinutes(90).excessBreakCountInPeriod(1)
                        .hasPendingRegularization(true).build()).getType());
    }

    @Test
    void bothRulesAreIndependent_earlyGoingOnDoesNotEnableBreak() {
        policy(earlyGoing(0, 0, 1));
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(ctx(ExceptionType.EXCESS_BREAK).breakMinutes(300).excessBreakCountInPeriod(1).build()).getType());
    }
}
