package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeavePlanAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LeavePlanAllocationRepository extends JpaRepository<LeavePlanAllocation, UUID> {

    List<LeavePlanAllocation> findByEmployeeUserIdOrderByEffectiveFromDesc(UUID employeeUserId);

    List<LeavePlanAllocation> findByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    long countByPlanId(UUID planId);

    /**
     * The employee's other rows overlapping [from, to] (a null {@code to} = open-ended), for rejecting a
     * write that would give one employee two plans on a date. Same contract as
     * {@link PenalizationPolicyAllocationRepository#findOverlapping}.
     */
    @Query("SELECT a FROM LeavePlanAllocation a WHERE a.employeeUserId = :employeeUserId "
            + "AND (:excludeId IS NULL OR a.id <> :excludeId) "
            + "AND (a.effectiveTo IS NULL OR a.effectiveTo >= :from) "
            + "AND (:to IS NULL OR a.effectiveFrom <= :to) "
            + "ORDER BY a.effectiveFrom")
    List<LeavePlanAllocation> findOverlapping(@Param("employeeUserId") UUID employeeUserId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to,
                                              @Param("excludeId") UUID excludeId);
}
