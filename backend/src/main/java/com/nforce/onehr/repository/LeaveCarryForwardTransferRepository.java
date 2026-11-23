package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveCarryForwardTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveCarryForwardTransferRepository extends JpaRepository<LeaveCarryForwardTransfer, UUID> {

    Optional<LeaveCarryForwardTransfer> findByEmployeeUserIdAndLeaveTypeIdAndFromYear(UUID employeeUserId, UUID leaveTypeId, Integer fromYear);

    @Query("SELECT t FROM LeaveCarryForwardTransfer t JOIN FETCH t.leaveType WHERE t.fromYear = :fromYear ORDER BY t.processedAt DESC")
    List<LeaveCarryForwardTransfer> findByFromYear(@Param("fromYear") Integer fromYear);

    boolean existsByLeaveTypeId(UUID leaveTypeId);
}
