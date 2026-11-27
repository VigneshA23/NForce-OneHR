package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveExitSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LeaveExitSettlementRepository extends JpaRepository<LeaveExitSettlement, UUID> {

    List<LeaveExitSettlement> findByEmployeeUserId(UUID employeeUserId);

    List<LeaveExitSettlement> findByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    List<LeaveExitSettlement> findByLastWorkingDayBetweenOrderByLastWorkingDayAsc(LocalDate from, LocalDate to);
}
