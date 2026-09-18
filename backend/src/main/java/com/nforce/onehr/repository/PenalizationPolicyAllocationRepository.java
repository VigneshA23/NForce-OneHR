package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenalizationPolicyAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PenalizationPolicyAllocationRepository extends JpaRepository<PenalizationPolicyAllocation, UUID> {

    /**
     * The allocation row in effect for this employee on {@code pointInTime} — same "effective at"
     * contract as {@link PenalizationPolicyVersionRepository#findVersionsEffectiveAtForPolicy},
     * ORDER BY createdAt DESC as a tie-break in the (should-never-happen, prevented at write time)
     * case of an overlap. Read fresh from the database on every call, no caching, so a saved
     * allocation is visible to the very next attendance evaluation.
     */
    @Query("SELECT a FROM PenalizationPolicyAllocation a WHERE a.employeeUserId = :employeeUserId "
            + "AND a.effectiveFrom <= :pointInTime AND (a.effectiveTo IS NULL OR a.effectiveTo >= :pointInTime) "
            + "ORDER BY a.createdAt DESC")
    List<PenalizationPolicyAllocation> findEffectiveAt(@Param("employeeUserId") UUID employeeUserId,
                                                         @Param("pointInTime") LocalDate pointInTime);

    List<PenalizationPolicyAllocation> findByEmployeeUserIdOrderByEffectiveFromDesc(UUID employeeUserId);

    List<PenalizationPolicyAllocation> findByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    /**
     * Every other allocation row for this employee that overlaps {@code [from, to]} — a null
     * {@code to} means "open-ended, extends to +infinity" on either side of the comparison. Used
     * to reject a write that would create two allocation rows covering the same date for the same
     * employee (non-destructive overlap prevention — the caller rejects rather than silently
     * truncating/deleting the conflicting row). Pass {@code excludeId = null} when checking a
     * brand-new allocation that has no id of its own yet.
     */
    @Query("SELECT a FROM PenalizationPolicyAllocation a WHERE a.employeeUserId = :employeeUserId "
            + "AND (:excludeId IS NULL OR a.id <> :excludeId) "
            + "AND (a.effectiveTo IS NULL OR a.effectiveTo >= :from) "
            + "AND (:to IS NULL OR a.effectiveFrom <= :to)")
    List<PenalizationPolicyAllocation> findOverlapping(@Param("employeeUserId") UUID employeeUserId,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to,
                                                          @Param("excludeId") UUID excludeId);

    long countByPenalisationPolicyId(UUID penalisationPolicyId);

    /**
     * (employeeUserId, penalisationPolicyId, createdAt) for every allocation row effective on
     * {@code pointInTime}, across every employee and every policy — the one bulk read
     * {@link com.nforce.onehr.service.PenalizationPolicyResolutionService#resolveCurrentPolicyIdsByEmployee}
     * uses to build the authoritative "who currently has which policy" answer, instead of one
     * query per policy or one per employee.
     */
    @Query("SELECT a.employeeUserId, a.penalisationPolicyId, a.createdAt FROM PenalizationPolicyAllocation a "
            + "WHERE a.effectiveFrom <= :pointInTime AND (a.effectiveTo IS NULL OR a.effectiveTo >= :pointInTime)")
    List<Object[]> findCurrentAllocationsAt(@Param("pointInTime") LocalDate pointInTime);

    Optional<PenalizationPolicyAllocation> findByIdAndEmployeeUserId(UUID id, UUID employeeUserId);
}
