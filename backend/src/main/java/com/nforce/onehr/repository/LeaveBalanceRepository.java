package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {

    List<LeaveBalance> findByEmployeeUserIdAndYear(UUID employeeUserId, Integer year);

    Optional<LeaveBalance> findByEmployeeUserIdAndLeaveTypeIdAndYear(UUID employeeUserId, UUID leaveTypeId, Integer year);
}
