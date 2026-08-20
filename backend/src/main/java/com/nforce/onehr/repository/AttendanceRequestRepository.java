package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendanceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface AttendanceRequestRepository extends JpaRepository<AttendanceRequest, UUID> {

    List<AttendanceRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<AttendanceRequest> findByStatus(String status);

    // Backs the Partial Day monthly-hours cap — see AttendanceRequestService.resolvePartialDayHours.
    List<AttendanceRequest> findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
            UUID employeeUserId, String requestType, LocalDate from, LocalDate to);

    // Backs the WFH one-request-per-date rule — see AttendanceRequestService.submit. Partial Day
    // deliberately has no equivalent: multiple same-day Partial Day requests are fine as long as
    // their combined minutes stay within the monthly cap (see partialDayHoursUsedInMonth).
    List<AttendanceRequest> findByEmployeeUserIdAndRequestTypeAndRequestDate(
            UUID employeeUserId, String requestType, LocalDate requestDate);
}
