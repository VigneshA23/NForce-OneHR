package com.nforce.onehr.repository;

import com.nforce.onehr.entity.WebClockInRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebClockInRequestRepository extends JpaRepository<WebClockInRequest, UUID> {

    List<WebClockInRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<WebClockInRequest> findByStatus(String status);

    Optional<WebClockInRequest> findByEmployeeUserIdAndWorkDateAndStatus(
            UUID employeeUserId, LocalDate workDate, String status);

    boolean existsByEmployeeUserIdAndWorkDateAndStatus(UUID employeeUserId, LocalDate workDate, String status);
}
