package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    // LEFT JOIN FETCH u.roles avoids an N+1: User.roles is EAGER, so without fetching it here
    // Hibernate would issue one extra "load roles" query per distinct user the moment each
    // Employee's User is initialized — 89 employees meant ~89 extra round trips (plus whatever
    // each caller's own per-employee lookups add on top), enough to make this take over a
    // minute against a remote DB. DISTINCT dedupes the root Employee rows the roles join
    // otherwise multiplies (a user with 2 roles would otherwise appear twice).
    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH u.roles LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL")
    List<Employee> findAllWithDetails();

    // Backs WorkingDayService callers (Team Effort, Team Punctuality) and the Penalty list —
    // joins the associations those read (location, weeklyOffPolicy, designation, department) in
    // one query instead of one lazy-load per employee.
    @Query("SELECT e FROM Employee e LEFT JOIN FETCH e.location LEFT JOIN FETCH e.weeklyOffPolicy "
         + "LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.department WHERE e.userId IN :ids")
    List<Employee> findAllByIdWithScheduleDetails(@Param("ids") Collection<UUID> ids);

    // Backs the shift/weekly-off import (ONEHR-108) — rows are addressed by employee code, not id.
    Optional<Employee> findByEmployeeCode(String employeeCode);

    // Backs EmployeeCodeGenerator#claim's availability check for a manually-entered Employee
    // ID — the employees.employee_code UNIQUE constraint is still the final backstop against a
    // race between two such checks.
    boolean existsByEmployeeCode(String employeeCode);

    // Scoped to a non-deleted user: several test emails in this dataset have been
    // re-registered after a soft delete (same email, new user row), and an unscoped
    // lookup here matches every row for that email — Spring Data throws
    // IncorrectResultSizeDataAccessException the moment there's more than one.
    @Query("SELECT e FROM Employee e JOIN FETCH e.user u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<Employee> findByUser_Email(@Param("email") String email);

    @Query("SELECT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true")
    List<Employee> findAllActiveWithDetails();

    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true AND u.id NOT IN (SELECT u2.id FROM User u2 JOIN u2.roles r WHERE r.code IN ('HR_ADMIN', 'SUPER_ADMIN'))")
    List<Employee> findAllActiveNonAdminWithDetails();

    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true AND u.id IN (SELECT u2.id FROM User u2 JOIN u2.roles r WHERE r.code IN :roleCodes)")
    List<Employee> findActiveByRoleCodes(@Param("roleCodes") Set<String> roleCodes);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.department.id = :id AND u.deletedAt IS NULL")
    long countByDepartmentId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.designation.id = :id AND u.deletedAt IS NULL")
    long countByDesignationId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.location.id = :id AND u.deletedAt IS NULL")
    long countByLocationId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.shift.id = :id AND u.deletedAt IS NULL")
    long countByShiftId(@Param("id") UUID id);

    // Batch equivalents of the 4 single-id counts above, one GROUP BY query each instead of one
    // COUNT query per row — backs OrgService's listDepartments/listDesignations/listLocations/
    // listShifts, which used to issue N extra round trips for N master-data rows on every
    // Organization Masters page load (and every Add/Edit User modal open, for the Shift
    // dropdown). Each returns Object[]{id (UUID), count (Long)}; a master-data row with zero
    // employees simply has no entry — callers default to 0.
    @Query("SELECT e.department.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.department IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.department.id")
    List<Object[]> countGroupedByDepartmentId();

    @Query("SELECT e.designation.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.designation IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.designation.id")
    List<Object[]> countGroupedByDesignationId();

    @Query("SELECT e.location.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.location IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.location.id")
    List<Object[]> countGroupedByLocationId();

    @Query("SELECT e.shift.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.shift IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.shift.id")
    List<Object[]> countGroupedByShiftId();

    // Backs the Shifts master-data "Employees" drill-down (Organization Masters → Shifts) —
    // same non-deleted scoping as countByShiftId, with department fetched to avoid an N+1.
    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department "
         + "WHERE e.shift.id = :shiftId AND u.deletedAt IS NULL ORDER BY e.fullName")
    List<Employee> findByShiftIdWithDetails(@Param("shiftId") UUID shiftId);

    @Query("SELECT e.userId, e.fullName FROM Employee e WHERE e.userId IN :ids")
    List<Object[]> findNamesByUserIds(@Param("ids") Set<UUID> ids);

    // Backs ShiftSeedCorrector's startup backfill for employees created after V95's one-time
    // "assign everyone the Regular Shift" migration ran (e.g. anyone onboarded since).
    List<Employee> findByShiftIsNull();

    // Backs the Policy List's "Employee Count" column (Section 5).
    long countByPenalisationPolicy_Id(UUID penalisationPolicyId);
}
