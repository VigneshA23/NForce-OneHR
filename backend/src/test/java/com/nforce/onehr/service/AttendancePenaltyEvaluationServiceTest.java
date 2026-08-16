package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the seam with a mock {@link AttendancePolicyEngine} — {@link ConfiguredAttendancePolicyEngineTest}
 * covers the real engine's decision logic; this test only covers what
 * {@link AttendancePenaltyEvaluationService} itself does with whatever decision it receives.
 */
@ExtendWith(MockitoExtension.class)
class AttendancePenaltyEvaluationServiceTest {

    @Mock private AttendancePolicyEngine policyEngine;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private PenaltyDeductionService penaltyDeductionService;

    @InjectMocks private AttendancePenaltyEvaluationService service;

    @Test
    void noMatch_persistsNothing() {
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder().type(PolicyDecisionType.NO_MATCH).build());

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(LocalDate.now())
                .discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isEmpty());
        verifyNoInteractions(attendancePenaltyRepository);
    }

    @Test
    void exempt_persistsNothing() {
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder().type(PolicyDecisionType.EXEMPT).build());

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(LocalDate.now())
                .discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isEmpty());
        verifyNoInteractions(attendancePenaltyRepository);
    }

    @Test
    void configurationRequired_persistsNothing() {
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder().type(PolicyDecisionType.CONFIGURATION_REQUIRED).build());

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(LocalDate.now())
                .discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isEmpty());
    }

    @Test
    void applyPenalty_persistsWithPolicySnapshot() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        UUID policyId = UUID.randomUUID();
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(policyId).policyVersion(2).build());
        when(attendancePenaltyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isPresent());
        assertEquals(employeeId, result.get().getEmployeeUserId());
        assertEquals(date, result.get().getIncidentDate());
        assertEquals(policyId, result.get().getPolicyId());
        assertEquals(2, result.get().getPolicyVersion());
        assertNotNull(result.get().getEvaluatedAt());
        verify(attendancePenaltyRepository).save(any());
    }

    @Test
    void applyPenalty_penaltyAlreadyExistsForThisIncident_doesNotSaveDuplicate() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(UUID.randomUUID()).policyVersion(1).build());
        when(attendancePenaltyRepository.existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
                employeeId, date, ExceptionType.LATE_ARRIVAL)).thenReturn(true);

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isEmpty());
        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
        // The duplicate guard short-circuits BEFORE deduction is even attempted — an already-
        // recorded penalty must never trigger a second leave-balance debit.
        verifyNoInteractions(penaltyDeductionService);
    }

    /** Idempotency (Section 18): evaluating the identical occurrence twice in a row must debit at most once. */
    @Test
    void evaluatingSameOccurrenceTwiceInARow_secondCallIsANoOp_noDoubleDeduction() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        PolicyEvaluationContext context = PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build();
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(UUID.randomUUID()).policyVersion(1)
                .deductionDays(new java.math.BigDecimal("1")).deductionMethod("PAID_LEAVE").build());
        when(attendancePenaltyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // First call: nothing recorded yet. Second call: the repository now reflects the row the
        // first call persisted — simulating two sequential runs against the same underlying state
        // (e.g. a dashboard reload, or the scheduler running twice for the same date).
        when(attendancePenaltyRepository.existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
                employeeId, date, ExceptionType.LATE_ARRIVAL)).thenReturn(false, true);

        Optional<AttendancePenalty> first = service.evaluate(context);
        Optional<AttendancePenalty> second = service.evaluate(context);

        assertTrue(first.isPresent(), "first evaluation creates the penalty");
        assertTrue(second.isEmpty(), "second evaluation of the identical occurrence is a no-op");
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(1)).save(any());
        verify(penaltyDeductionService, org.mockito.Mockito.times(1)).apply(any(), any(), any());
    }
}
