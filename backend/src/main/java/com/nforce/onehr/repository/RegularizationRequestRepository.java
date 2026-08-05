package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RegularizationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RegularizationRequestRepository extends JpaRepository<RegularizationRequest, UUID> {

    List<RegularizationRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<RegularizationRequest> findByStatus(String status);

    List<RegularizationRequest> findByStatusIn(Collection<String> statuses);

    boolean existsByEmployeeUserIdAndAttendanceDateAndStatus(UUID employeeUserId, LocalDate attendanceDate, String status);

    /** Backs the monthly submission-limit check — counts every request regardless of status. */
    long countByEmployeeUserIdAndCreatedAtBetween(UUID employeeUserId, LocalDateTime start, LocalDateTime end);
}
