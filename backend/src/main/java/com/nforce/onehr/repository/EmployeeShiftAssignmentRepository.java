package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeShiftAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
