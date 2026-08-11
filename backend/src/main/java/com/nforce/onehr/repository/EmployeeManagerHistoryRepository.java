package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeManagerHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeManagerHistoryRepository extends JpaRepository<EmployeeManagerHistory, Long> {

    Optional<EmployeeManagerHistory> findByEmployeeUserIdAndEffectiveToIsNull(UUID employeeUserId);

    List<EmployeeManagerHistory> findByEffectiveToIsNull();

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

    // Current "peers" of an employee — everyone who presently shares the same manager
    // (siblings in the reporting line), excluding the employee themself. This is an interim
    // stand-in for "peer group" (ONEHR-73): there is no Team/Project/Squad grouping entity in
    // the data model yet, so same-manager is the narrowest real relationship we can query today.
    // Revisit if/when a proper team-membership concept is introduced.
    @Query("SELECT h2.employeeUserId FROM EmployeeManagerHistory h1 "
         + "JOIN EmployeeManagerHistory h2 ON h2.managerUserId = h1.managerUserId AND h2.effectiveTo IS NULL "
         + "WHERE h1.employeeUserId = :employeeId AND h1.effectiveTo IS NULL AND h2.employeeUserId <> :employeeId")
    List<UUID> findCurrentPeerIds(UUID employeeId);

    @Modifying
    @Query("UPDATE EmployeeManagerHistory h SET h.effectiveTo = :now WHERE h.employeeUserId = :employeeId AND h.effectiveTo IS NULL")
    void closeCurrentEntry(UUID employeeId, LocalDateTime now);
}
