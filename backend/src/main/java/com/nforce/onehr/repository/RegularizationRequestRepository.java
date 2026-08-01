package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RegularizationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface RegularizationRequestRepository extends JpaRepository<RegularizationRequest, UUID> {

    List<RegularizationRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<RegularizationRequest> findByStatus(String status);

    boolean existsByEmployeeUserIdAndAttendanceDateAndStatus(UUID employeeUserId, LocalDate attendanceDate, String status);
}
