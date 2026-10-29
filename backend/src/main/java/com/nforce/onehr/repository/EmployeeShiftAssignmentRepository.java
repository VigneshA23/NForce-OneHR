package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeShiftAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeShiftAssignmentRepository extends JpaRepository<EmployeeShiftAssignment, UUID> {

    /** The resolver's one core query: the latest assignment whose effectiveFrom <= workDate. */
    Optional<EmployeeShiftAssignment> findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(UUID employeeUserId, LocalDate workDate);

    /** At most one row is ever expected (see the "at most one pending assignment" rule enforced in EmployeeAssignmentService) — used to find it for display/replacement, and by EmployeeAssignmentService#assignShift to clear it before inserting a new one, regardless of the new assignment's own date. */
    Optional<EmployeeShiftAssignment> findFirstByEmployeeUserIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(UUID employeeUserId, LocalDate today);

    /** Full history for an employee, newest first — mirrors ShiftVersionRepository's identical drill-down query. */
    List<EmployeeShiftAssignment> findByEmployeeUserIdOrderByEffectiveFromDesc(UUID employeeUserId);

    /**
     * Batch equivalent of {@link com.nforce.onehr.service.EmployeeShiftAssignmentResolver#resolveIfPresent}
     * for a whole team at once (see {@code EmployeeAssignmentService#listTeamAssignments}) — every assignment
     * effective on or before {@code asOf} for these employees, ordered so the LATEST one per
     * employee (i.e. the one actually governing {@code asOf}) comes first within that employee's
     * own rows; reduced to "one row per employee" in application code (a single pass keeping only
     * the first row seen per {@code employeeUserId}) rather than a correlated subquery, to stay
     * portable and simple.
     */
    List<EmployeeShiftAssignment> findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(
            Collection<UUID> employeeUserIds, LocalDate asOf);

    /**
     * Batch equivalent of {@code findFirstByEmployeeUserIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc}
     * for a whole team at once — every PENDING (not yet effective) assignment for these employees,
     * ordered so the EARLIEST one per employee comes first; reduced to "one row per employee" the
     * same way as the batch query above (at most one pending row is ever expected per employee —
     * see this repository's own class-level rule).
     */
    List<EmployeeShiftAssignment> findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(
            Collection<UUID> employeeUserIds, LocalDate today);

    /**
     * Used exclusively by {@code OrgService#deleteShift}'s historical/pending-usage guard — a
     * Shift can still be referenced here (a past or future-scheduled assignment) even with zero
     * *current* employees assigned and zero Attendance rows, since this table has no
     * {@code ON DELETE} clause on its own {@code shift_id} FK (see migration V172); without this
     * check, that case fell straight through to a raw, un-translated FK-violation error instead of
     * the same clear "Deactivate it instead" message the Attendance check already produces.
     */
    boolean existsByShiftId(UUID shiftId);

    /**
     * True when at least one employee currently has {@code shiftId} as the shift actually
     * governing {@code today} (the same "latest effectiveFrom <= today" resolution
     * {@link com.nforce.onehr.service.EmployeeShiftAssignmentResolver} uses), or has a
     * future-dated (not-yet-effective) assignment to it — used by
     * {@code OrgService#toggleShiftActive}'s D7 deactivation guard. Deliberately narrower than
     * {@link #existsByShiftId}, which also counts purely historical/superseded rows and backs
     * the DELETE guard instead: a shift every current assignee has since moved away from must
     * remain deactivatable.
     */
    @Query("""
            SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END
            FROM EmployeeShiftAssignment a
            WHERE a.shift.id = :shiftId
              AND (a.effectiveFrom > :today
                   OR a.effectiveFrom = (
                        SELECT MAX(a2.effectiveFrom) FROM EmployeeShiftAssignment a2
                        WHERE a2.employeeUserId = a.employeeUserId AND a2.effectiveFrom <= :today))
            """)
    boolean existsCurrentOrFutureAssignmentForShift(@Param("shiftId") UUID shiftId, @Param("today") LocalDate today);
}
