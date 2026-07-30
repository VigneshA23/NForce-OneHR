package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    List<LeaveRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<LeaveRequest> findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(Collection<UUID> employeeUserIds, String status);
}
