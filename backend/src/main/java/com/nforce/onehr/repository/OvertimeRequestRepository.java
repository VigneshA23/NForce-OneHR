package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OvertimeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OvertimeRequestRepository extends JpaRepository<OvertimeRequest, UUID> {

    List<OvertimeRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<OvertimeRequest> findByStatus(String status);
}
