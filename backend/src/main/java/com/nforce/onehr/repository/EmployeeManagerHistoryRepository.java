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

    @Modifying
    @Query("UPDATE EmployeeManagerHistory h SET h.effectiveTo = :now WHERE h.employeeUserId = :employeeId AND h.effectiveTo IS NULL")
    void closeCurrentEntry(UUID employeeId, LocalDateTime now);
}
