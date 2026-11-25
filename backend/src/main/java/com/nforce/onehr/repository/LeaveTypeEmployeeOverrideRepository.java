package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveTypeEmployeeOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaveTypeEmployeeOverrideRepository extends JpaRepository<LeaveTypeEmployeeOverride, UUID> {

    List<LeaveTypeEmployeeOverride> findByLeaveTypeId(UUID leaveTypeId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM LeaveTypeEmployeeOverride o WHERE o.leaveTypeId = :typeId")
    void deleteByLeaveTypeId(@Param("typeId") UUID typeId);
}
