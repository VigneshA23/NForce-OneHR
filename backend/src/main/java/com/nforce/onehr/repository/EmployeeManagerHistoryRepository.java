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

    // Current direct reports of a manager — effective_to IS NULL is the open row,
    // same convention as findByEmployeeUserIdAndEffectiveToIsNull above.
    @Query("SELECT h.employeeUserId FROM EmployeeManagerHistory h "
         + "WHERE h.managerUserId = :managerId AND h.effectiveTo IS NULL")
    List<UUID> findCurrentDirectReportIds(UUID managerId);

    @Modifying
    @Query("UPDATE EmployeeManagerHistory h SET h.effectiveTo = :now WHERE h.employeeUserId = :employeeId AND h.effectiveTo IS NULL")
    void closeCurrentEntry(UUID employeeId, LocalDateTime now);
}
