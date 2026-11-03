package com.nforce.onehr.repository;

import com.nforce.onehr.entity.WeeklyOffPolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WeeklyOffPolicyVersionRepository extends JpaRepository<WeeklyOffPolicyVersion, UUID> {

    /** The resolver's one core single-policy query: the latest version whose effectiveFrom <= workDate. */
    Optional<WeeklyOffPolicyVersion> findFirstByWeeklyOffPolicyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            UUID weeklyOffPolicyId, LocalDate workDate);

    /** At most one row is ever expected effective today-or-later per policy (see the "replace the same-day version" rule in OrgService). */
    Optional<WeeklyOffPolicyVersion> findFirstByWeeklyOffPolicyIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(
            UUID weeklyOffPolicyId, LocalDate today);

    /** Bulk-safe replace: clears any version(s) effective today-or-later before inserting a new one. */
    void deleteByWeeklyOffPolicyIdAndEffectiveFromGreaterThanEqual(UUID weeklyOffPolicyId, LocalDate today);

    /** Full history for a policy, newest first — mirrors ShiftVersionRepository's identical drill-down query. */
    List<WeeklyOffPolicyVersion> findByWeeklyOffPolicyIdOrderByEffectiveFromDesc(UUID weeklyOffPolicyId);

    /**
     * Batch equivalent for a whole set of policies at once — every version for these policies,
     * ordered so each policy's own versions are ascending by effectiveFrom (oldest first), ready
     * for an in-memory "latest effectiveFrom <= date" scan per policy without a further query per
     * date. Used by WorkingDayService/ExceptionService's bulk, multi-date calculations to avoid
     * re-querying per employee per date (the same batching precedent
     * EmployeeShiftAssignmentRepository's own batch queries already established).
     */
    List<WeeklyOffPolicyVersion> findByWeeklyOffPolicyIdInOrderByWeeklyOffPolicyIdAscEffectiveFromAsc(
            Collection<UUID> weeklyOffPolicyIds);
}
