package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveEncashment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LeaveEncashmentRepository extends JpaRepository<LeaveEncashment, UUID> {

    List<LeaveEncashment> findByRunId(UUID runId);

    List<LeaveEncashment> findByStatusAndEmployeeUserIdIn(String status, Collection<UUID> employeeUserIds);
}
