package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeManagerHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeManagerHistoryRepository extends JpaRepository<EmployeeManagerHistory, Long> {

    Optional<EmployeeManagerHistory> findByEmployeeUserIdAndEffectiveToIsNull(UUID employeeUserId);

    List<EmployeeManagerHistory> findByEffectiveToIsNull();

    // Backs EmployeeService#findCurrentManagersBulk in one query instead of three (history, then
    // a separate findAllById against User and against Employee for the same manager ids) — an
    // ad-hoc join to User (managerUserId is a plain UUID column here, not a mapped relationship)
    // plus a LEFT JOIN to Employee, since a manager can have a User row with no Employee row (the
    // caller falls back to email as the display name in that case — see managerFullName below,
    // which is null for exactly that case). Returns (employeeUserId, managerUserId, managerEmail,
    // managerFullName-or-null) for every row in effect for the given employees.
    @Query("SELECT h.employeeUserId, u.id, u.email, e.fullName "
         + "FROM EmployeeManagerHistory h JOIN User u ON u.id = h.managerUserId LEFT JOIN Employee e ON e.userId = u.id "
         + "WHERE h.employeeUserId IN :employeeUserIds AND h.effectiveTo IS NULL")
    List<Object[]> findCurrentManagerInfoByEmployeeIds(@Param("employeeUserIds") Collection<UUID> employeeUserIds);

    List<EmployeeManagerHistory> findByManagerUserIdAndEffectiveToIsNull(UUID managerUserId);

    // All history rows (open or closed) for a manager with effectiveFrom in a window — used
    // for "team joiners" reporting, where someone reassigned away since joining should still
    // count, unlike findByManagerUserIdAndEffectiveToIsNull above which only sees current reports.
    List<EmployeeManagerHistory> findByManagerUserIdAndEffectiveFromGreaterThanEqual(UUID managerUserId, LocalDateTime since);

    // Current direct reports of a manager — effective_to IS NULL is the open row,
    // same convention as findByEmployeeUserIdAndEffectiveToIsNull above.
    @Query("SELECT h.employeeUserId FROM EmployeeManagerHistory h "
         + "WHERE h.managerUserId = :managerId AND h.effectiveTo IS NULL")
    List<UUID> findCurrentDirectReportIds(UUID managerId);

    // Current "Project Team" of an employee — everyone (including the employee themself) who
    // presently shares the same manager. This is an interim stand-in for "peer group" (ONEHR-73):
    // there is no Team/Project/Squad grouping entity in the data model yet, so same-manager is
    // the narrowest real relationship we can query today. Revisit if/when a proper
    // team-membership concept is introduced. Deliberately includes the caller (not just their
    // siblings) so "Project Team" reads as "my team," not "everyone but me."
    @Query("SELECT h2.employeeUserId FROM EmployeeManagerHistory h1 "
         + "JOIN EmployeeManagerHistory h2 ON h2.managerUserId = h1.managerUserId AND h2.effectiveTo IS NULL "
         + "WHERE h1.employeeUserId = :employeeId AND h1.effectiveTo IS NULL")
    List<UUID> findCurrentPeerIds(UUID employeeId);

    @Modifying
    @Query("UPDATE EmployeeManagerHistory h SET h.effectiveTo = :now WHERE h.employeeUserId = :employeeId AND h.effectiveTo IS NULL")
    void closeCurrentEntry(UUID employeeId, LocalDateTime now);
}
