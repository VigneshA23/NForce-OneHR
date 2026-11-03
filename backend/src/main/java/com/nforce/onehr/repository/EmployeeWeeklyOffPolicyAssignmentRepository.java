package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeWeeklyOffPolicyAssignmentRepository extends JpaRepository<EmployeeWeeklyOffPolicyAssignment, UUID> {

    /** The resolver's one core query: the latest assignment whose effectiveFrom <= workDate. */
    Optional<EmployeeWeeklyOffPolicyAssignment> findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            UUID employeeUserId, LocalDate workDate);

    /** At most one row is ever expected (see the "at most one pending assignment" rule enforced in EmployeeAssignmentService). */
    Optional<EmployeeWeeklyOffPolicyAssignment> findFirstByEmployeeUserIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(
            UUID employeeUserId, LocalDate today);

    /** Full history for an employee, newest first — mirrors EmployeeShiftAssignmentRepository's identical drill-down query. */
    List<EmployeeWeeklyOffPolicyAssignment> findByEmployeeUserIdOrderByEffectiveFromDesc(UUID employeeUserId);

    /**
     * Batch equivalent of {@code findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc}
     * for a whole team at once — mirrors EmployeeShiftAssignmentRepository's identical query, used
     * by EmployeeAssignmentService#listTeamAssignments/bulkUpdateWeeklyOff for the "current"
     * display/replacement decision.
     */
    List<EmployeeWeeklyOffPolicyAssignment> findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(
            Collection<UUID> employeeUserIds, LocalDate asOf);

    /**
     * Batch equivalent of {@code findFirstByEmployeeUserIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc}
     * for a whole team at once — mirrors EmployeeShiftAssignmentRepository's identical query, used
     * by EmployeeAssignmentService#listTeamAssignments/bulkUpdateWeeklyOff for the "pending"
     * display/replacement decision.
     */
    List<EmployeeWeeklyOffPolicyAssignment> findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(
            Collection<UUID> employeeUserIds, LocalDate today);

    /**
     * Batch equivalent of {@link com.nforce.onehr.service.EmployeeWeeklyOffPolicyAssignmentResolver#resolveIfPresent}
     * for a whole set of employees at once — every assignment for these employees, ordered so
     * each employee's own rows are ascending by effectiveFrom (oldest first), ready for an
     * in-memory "latest effectiveFrom <= date" scan per employee without a further query per date.
     */
    List<EmployeeWeeklyOffPolicyAssignment> findByEmployeeUserIdInOrderByEmployeeUserIdAscEffectiveFromAsc(
            Collection<UUID> employeeUserIds);

    /**
     * Used exclusively by {@code OrgService#deleteWeeklyOffPolicy}'s historical/pending-usage
     * guard — mirrors {@code EmployeeShiftAssignmentRepository#existsByShiftId}'s identical
     * reasoning: this table's own {@code weekly_off_policy_id} FK has no {@code ON DELETE} clause,
     * so a policy referenced only by assignment history (zero *current* employees) would otherwise
     * fail deletion at the raw database FK instead of with a clear, actionable message.
     */
    boolean existsByWeeklyOffPolicyId(UUID weeklyOffPolicyId);

    /**
     * Audit fix (Phase 3 verification): the number of non-deleted employees whose CURRENTLY-
     * governing assignment (latest {@code effectiveFrom <= asOf}) points to {@code policyId} —
     * never {@code Employee.weeklyOffPolicy}, which {@code EmployeeAssignmentService#
     * bulkUpdateWeeklyOff}/CSV import no longer write to at all as of this phase, making
     * {@code EmployeeRepository#countByWeeklyOffPolicyId} permanently stale for any employee ever
     * reassigned through this table. Backs {@code OrgService#deleteWeeklyOffPolicy}'s specific
     * "N current employees" guard message and {@code #updateWeeklyOffPolicy}'s response count.
     */
    @Query("""
            SELECT COUNT(DISTINCT a.employeeUserId)
            FROM EmployeeWeeklyOffPolicyAssignment a
            WHERE a.weeklyOffPolicy.id = :policyId
              AND a.effectiveFrom = (
                    SELECT MAX(a2.effectiveFrom) FROM EmployeeWeeklyOffPolicyAssignment a2
                    WHERE a2.employeeUserId = a.employeeUserId AND a2.effectiveFrom <= :asOf)
              AND EXISTS (SELECT 1 FROM Employee e WHERE e.userId = a.employeeUserId AND e.user.deletedAt IS NULL)
            """)
    long countCurrentlyAssigned(@Param("policyId") UUID policyId, @Param("asOf") LocalDate asOf);

    /**
     * Batch equivalent of {@link #countCurrentlyAssigned} across every policy at once — one query
     * for the whole Weekly Off Policies list, mirroring {@code EmployeeRepository
     * #countGroupedByShiftId}'s identical batching shape ({@code Object[]{id, count}}) so {@code
     * OrgService#listWeeklyOffPolicies} can reuse its existing {@code toCountMap} helper unchanged.
     */
    @Query("""
            SELECT a.weeklyOffPolicy.id, COUNT(DISTINCT a.employeeUserId)
            FROM EmployeeWeeklyOffPolicyAssignment a
            WHERE a.effectiveFrom = (
                    SELECT MAX(a2.effectiveFrom) FROM EmployeeWeeklyOffPolicyAssignment a2
                    WHERE a2.employeeUserId = a.employeeUserId AND a2.effectiveFrom <= :asOf)
              AND EXISTS (SELECT 1 FROM Employee e WHERE e.userId = a.employeeUserId AND e.user.deletedAt IS NULL)
            GROUP BY a.weeklyOffPolicy.id
            """)
    List<Object[]> countCurrentlyAssignedGroupedByPolicy(@Param("asOf") LocalDate asOf);
}
