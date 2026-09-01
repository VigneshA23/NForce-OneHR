package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RegularizationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // Backs RegularizationService#listPendingForApprover — a company-wide (not manager-scoped)
    // queue, so unlike the employeeUserId-scoped queries elsewhere in this file, these need their
    // own User join to exclude soft-deleted requesters' regularization requests.
    @Query("SELECT r FROM RegularizationRequest r JOIN User u ON u.id = r.employeeUserId "
         + "WHERE r.status = :status AND u.deletedAt IS NULL")
    List<RegularizationRequest> findByStatus(@Param("status") String status);

    @Query("SELECT r FROM RegularizationRequest r JOIN User u ON u.id = r.employeeUserId "
         + "WHERE r.status IN :statuses AND u.deletedAt IS NULL")
    List<RegularizationRequest> findByStatusIn(@Param("statuses") Collection<String> statuses);

    // Backs RegularizationService#listAll (Super Admin full history) and #listForApprover's
    // override branch — both used the base findAll() previously, which returns every row
    // regardless of the requester's deletion state. A dedicated method rather than overriding
    // findAll() itself, matching the EmployeeRepository.findAllWithDetails() precedent for "the
    // same rows as findAll(), but deletedAt-aware."
    @Query("SELECT r FROM RegularizationRequest r JOIN User u ON u.id = r.employeeUserId WHERE u.deletedAt IS NULL")
    List<RegularizationRequest> findAllWithActiveRequester();

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
