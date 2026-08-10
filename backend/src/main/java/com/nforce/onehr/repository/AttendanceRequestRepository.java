package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendanceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AttendanceRequestRepository extends JpaRepository<AttendanceRequest, UUID> {

    List<AttendanceRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<AttendanceRequest> findByStatus(String status);
}
