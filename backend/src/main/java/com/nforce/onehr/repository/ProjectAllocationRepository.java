package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ProjectAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectAllocationRepository extends JpaRepository<ProjectAllocation, UUID> {

    /**
     * US-B04/B06/B10 capacity aggregation: total capacity% of the employee's allocations still active
     * somewhere in [startDate, endDate]; an allocation ended early stops at its actualEndDate.
     * Call it only through {@code ProjectAllocationService#allocatedCapacity}.
     */
    @Query("SELECT COALESCE(SUM(a.capacityPercent), 0) FROM ProjectAllocation a WHERE a.employeeUserId = :employeeUserId "
            + "AND a.startDate <= :endDate AND COALESCE(a.actualEndDate, a.endDate) >= :startDate")
    long sumOverlappingCapacity(@Param("employeeUserId") UUID employeeUserId,
                               @Param("startDate") LocalDate startDate,
                               @Param("endDate") LocalDate endDate);

    /**
     * US-B08: one employee's allocations that have not ended as of {@code today} — current and upcoming —
     * earliest start first. "Ended" uses the effective end (actualEndDate when released early, else endDate).
     * Read-only and scoped to a single employee id; call it only through {@code MyAllocationService}.
     */
    @Query("SELECT a FROM ProjectAllocation a WHERE a.employeeUserId = :employeeUserId "
            + "AND COALESCE(a.actualEndDate, a.endDate) >= :today ORDER BY a.startDate ASC, a.id ASC")
    List<ProjectAllocation> findCurrentAndUpcoming(@Param("employeeUserId") UUID employeeUserId,
                                                   @Param("today") LocalDate today);

    /** US-B13: "belongs to project X" — every employee with at least one allocation to it. */
    @Query("SELECT DISTINCT a.employeeUserId FROM ProjectAllocation a WHERE a.projectId = :projectId")
    List<UUID> findDistinctEmployeeUserIdsByProjectId(@Param("projectId") UUID projectId);

    /** Batch fetch for the capacity view — one query for every in-scope employee's allocations. */
    List<ProjectAllocation> findByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    /** US-A02 "last project": the allocation that ended (or ends) latest for each of these employees. */
    @Query("SELECT a FROM ProjectAllocation a WHERE a.employeeUserId IN :employeeUserIds AND a.endDate = ("
            + "SELECT MAX(b.endDate) FROM ProjectAllocation b WHERE b.employeeUserId = a.employeeUserId)")
    List<ProjectAllocation> findLatestEndingForEmployees(@Param("employeeUserIds") Collection<UUID> employeeUserIds);
}
