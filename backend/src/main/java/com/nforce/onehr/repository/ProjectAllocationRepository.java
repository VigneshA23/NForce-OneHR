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

    /** Date-range overlap: existing.start <= req.end AND existing.end >= req.start. */
    @Query("SELECT a FROM ProjectAllocation a WHERE a.employeeUserId = :employeeUserId "
            + "AND a.startDate <= :endDate AND a.endDate >= :startDate")
    List<ProjectAllocation> findOverlapping(@Param("employeeUserId") UUID employeeUserId,
                                             @Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);

    /** US-B13: "belongs to project X" — every employee with at least one allocation to it. */
    @Query("SELECT DISTINCT a.employeeUserId FROM ProjectAllocation a WHERE a.projectId = :projectId")
    List<UUID> findDistinctEmployeeUserIdsByProjectId(@Param("projectId") UUID projectId);

    /** Batch fetch for the capacity view — one query for every in-scope employee's allocations. */
    List<ProjectAllocation> findByEmployeeUserIdIn(Collection<UUID> employeeUserIds);
}
