package com.nforce.onehr.service;

import com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.repository.EmployeeWeeklyOffPolicyAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link EmployeeWeeklyOffPolicyAssignmentResolver}'s contract — mirrors {@link
 * EmployeeShiftAssignmentResolverTest}'s identical shape, one concept over (Weekly Off Policy
 * instead of Shift), except there is deliberately no throwing {@code resolve} here: no effective
 * assignment at all is an explicit, valid, already-handled state (the Saturday/Sunday fallback —
 * see WorkingDayService), never an invariant violation.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeWeeklyOffPolicyAssignmentResolverTest {

    @Mock private EmployeeWeeklyOffPolicyAssignmentRepository repository;

    private final UUID employeeUserId = UUID.randomUUID();
    private final WeeklyOffPolicy policy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("test").build();
    private final LocalDate day = LocalDate.of(2026, 9, 10);

    @Test
    void resolveIfPresent_returnsTheAssignmentWhenOneExists() {
        EmployeeWeeklyOffPolicyAssignmentResolver resolver = new EmployeeWeeklyOffPolicyAssignmentResolver(repository);
        EmployeeWeeklyOffPolicyAssignment assignment = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(employeeUserId).weeklyOffPolicy(policy).effectiveFrom(day).build();
        when(repository.findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, day))
                .thenReturn(Optional.of(assignment));

        assertEquals(Optional.of(assignment), resolver.resolveIfPresent(employeeUserId, day));
    }

    @Test
    void resolveIfPresent_returnsEmpty_whenNoAssignmentIsEffective() {
        EmployeeWeeklyOffPolicyAssignmentResolver resolver = new EmployeeWeeklyOffPolicyAssignmentResolver(repository);
        when(repository.findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, day))
                .thenReturn(Optional.empty());

        assertFalse(resolver.resolveIfPresent(employeeUserId, day).isPresent());
    }

    @Test
    void resolveHistoryBatch_groupsRowsByEmployee_preservingAscendingOrder() {
        EmployeeWeeklyOffPolicyAssignmentResolver resolver = new EmployeeWeeklyOffPolicyAssignmentResolver(repository);
        UUID otherEmployeeId = UUID.randomUUID();
        EmployeeWeeklyOffPolicyAssignment first = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(employeeUserId).weeklyOffPolicy(policy).effectiveFrom(day.minusDays(10)).build();
        EmployeeWeeklyOffPolicyAssignment second = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(employeeUserId).weeklyOffPolicy(policy).effectiveFrom(day).build();
        EmployeeWeeklyOffPolicyAssignment otherEmployeeRow = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(otherEmployeeId).weeklyOffPolicy(policy).effectiveFrom(day).build();
        when(repository.findByEmployeeUserIdInOrderByEmployeeUserIdAscEffectiveFromAsc(any()))
                .thenReturn(List.of(first, second, otherEmployeeRow));

        Map<UUID, List<EmployeeWeeklyOffPolicyAssignment>> history =
                resolver.resolveHistoryBatch(List.of(employeeUserId, otherEmployeeId));

        assertEquals(List.of(first, second), history.get(employeeUserId));
        assertEquals(List.of(otherEmployeeRow), history.get(otherEmployeeId));
    }

    @Test
    void resolveHistoryBatch_emptyInput_returnsEmptyMap_withNoQuery() {
        EmployeeWeeklyOffPolicyAssignmentResolver resolver = new EmployeeWeeklyOffPolicyAssignmentResolver(repository);

        assertTrue(resolver.resolveHistoryBatch(List.of()).isEmpty());
    }

    // ── governingAssignment (the in-memory batch-resolution helper) ──────────────────────────

    @Test
    void governingAssignment_returnsTheLatestEntryOnOrBeforeTheDate() {
        EmployeeWeeklyOffPolicyAssignment older = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(employeeUserId).weeklyOffPolicy(policy).effectiveFrom(day.minusDays(10)).build();
        EmployeeWeeklyOffPolicyAssignment newer = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(employeeUserId).weeklyOffPolicy(policy).effectiveFrom(day.minusDays(1)).build();

        Optional<EmployeeWeeklyOffPolicyAssignment> result =
                EmployeeWeeklyOffPolicyAssignmentResolver.governingAssignment(List.of(older, newer), day);

        assertEquals(Optional.of(newer), result);
    }

    @Test
    void governingAssignment_boundaryDate_exactEffectiveFromMatchIsIncluded() {
        EmployeeWeeklyOffPolicyAssignment assignment = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(employeeUserId).weeklyOffPolicy(policy).effectiveFrom(day).build();

        assertEquals(Optional.of(assignment),
                EmployeeWeeklyOffPolicyAssignmentResolver.governingAssignment(List.of(assignment), day));
    }

    @Test
    void governingAssignment_dateBeforeEarliestAssignment_returnsEmpty() {
        EmployeeWeeklyOffPolicyAssignment futureOnly = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(employeeUserId).weeklyOffPolicy(policy).effectiveFrom(day.plusDays(1)).build();

        assertTrue(EmployeeWeeklyOffPolicyAssignmentResolver.governingAssignment(List.of(futureOnly), day).isEmpty());
    }

    @Test
    void governingAssignment_emptyHistory_returnsEmpty() {
        assertTrue(EmployeeWeeklyOffPolicyAssignmentResolver.governingAssignment(List.of(), day).isEmpty());
    }

    @Test
    void governingAssignment_nullHistory_returnsEmpty() {
        assertTrue(EmployeeWeeklyOffPolicyAssignmentResolver.governingAssignment(null, day).isEmpty());
    }
}
