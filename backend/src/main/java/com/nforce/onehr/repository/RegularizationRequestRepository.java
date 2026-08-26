package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RegularizationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface RegularizationRequestRepository extends JpaRepository<RegularizationRequest, UUID> {

    List<RegularizationRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Backs audit-log target search — resolves which regularization requests belong to a set of employees.
    @Query("SELECT r.id FROM RegularizationRequest r WHERE r.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    List<RegularizationRequest> findByStatus(String status);

    List<RegularizationRequest> findByStatusIn(Collection<String> statuses);

    boolean existsByEmployeeUserIdAndAttendanceDateAndStatus(UUID employeeUserId, LocalDate attendanceDate, String status);

    /** Backs the monthly submission-limit check — counts every request regardless of status. */
    long countByEmployeeUserIdAndCreatedAtBetween(UUID employeeUserId, LocalDateTime start, LocalDateTime end);

    // Backs the "Attendance Regularizations Summary" report card (ONEHR-109) — a manager's
    // team, scoped by caller, over a date range.
    List<RegularizationRequest> findByEmployeeUserIdInAndAttendanceDateBetween(
            Collection<UUID> employeeUserIds, LocalDate from, LocalDate to);

    // Backs "View Regularization History" from the Penalties kebab menu — every request ever
    // filed for one employee/date, newest first.
    List<RegularizationRequest> findByEmployeeUserIdAndAttendanceDateOrderByCreatedAtDesc(
            UUID employeeUserId, LocalDate attendanceDate);
}
