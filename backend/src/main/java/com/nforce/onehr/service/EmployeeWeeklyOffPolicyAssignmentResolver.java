package com.nforce.onehr.service;

import com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment;
import com.nforce.onehr.repository.EmployeeWeeklyOffPolicyAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one centralized place every date-aware consumer resolves "which Weekly Off Policy governed
 * this employee on date D" — mirrors {@link EmployeeShiftAssignmentResolver}'s identical role one
 * layer up for WHICH Shift an employee is even on. Unlike that resolver, there is no throwing
 * {@code resolve} here: no effective assignment at all is an explicit, valid, already-handled
 * state (see {@code WorkingDayService}/{@code ExceptionService}'s existing Saturday/Sunday
 * fallback — D1, never a silently-assigned default policy), not an invariant violation.
 */
@Service
@RequiredArgsConstructor
public class EmployeeWeeklyOffPolicyAssignmentResolver {

    private final EmployeeWeeklyOffPolicyAssignmentRepository employeeWeeklyOffPolicyAssignmentRepository;

    /** The assignment governing {@code workDate}: the latest assignment whose {@code effectiveFrom} is on or before it. */
    public Optional<EmployeeWeeklyOffPolicyAssignment> resolveIfPresent(UUID employeeUserId, LocalDate workDate) {
        return employeeWeeklyOffPolicyAssignmentRepository
                .findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, workDate);
    }

    /**
     * Batch equivalent of {@link #resolveIfPresent} for a whole set of employees at once — one
     * query total, grouped by employee id with each employee's own rows ascending by
     * effectiveFrom, ready for {@link #governingAssignment} to scan in-memory per date without a
     * further query. Used by {@code WorkingDayService}'s bulk, multi-date calculations.
     */
    public Map<UUID, List<EmployeeWeeklyOffPolicyAssignment>> resolveHistoryBatch(Collection<UUID> employeeUserIds) {
        if (employeeUserIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<EmployeeWeeklyOffPolicyAssignment>> result = new HashMap<>();
        for (EmployeeWeeklyOffPolicyAssignment assignment : employeeWeeklyOffPolicyAssignmentRepository
                .findByEmployeeUserIdInOrderByEmployeeUserIdAscEffectiveFromAsc(employeeUserIds)) {
            result.computeIfAbsent(assignment.getEmployeeUserId(), k -> new ArrayList<>()).add(assignment);
        }
        return result;
    }

    /**
     * In-memory equivalent of {@link #resolveIfPresent}, given an employee's own assignment
     * history already sorted ascending by effectiveFrom (as {@link #resolveHistoryBatch}
     * returns) — the latest entry whose effectiveFrom is on or before {@code workDate}. A plain
     * reverse linear scan is intentional: an employee's own policy-assignment history is tiny
     * (reassignments are rare), so this stays simple rather than a binary search for a handful of
     * entries.
     */
    public static Optional<EmployeeWeeklyOffPolicyAssignment> governingAssignment(
            List<EmployeeWeeklyOffPolicyAssignment> historyAscending, LocalDate workDate) {
        if (historyAscending == null) {
            return Optional.empty();
        }
        for (int i = historyAscending.size() - 1; i >= 0; i--) {
            EmployeeWeeklyOffPolicyAssignment assignment = historyAscending.get(i);
            if (!assignment.getEffectiveFrom().isAfter(workDate)) {
                return Optional.of(assignment);
            }
        }
        return Optional.empty();
    }
}
