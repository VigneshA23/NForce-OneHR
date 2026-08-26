package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    List<LeaveRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Backs audit-log target search — resolves which leave requests belong to a set of employees.
    @Query("SELECT r.id FROM LeaveRequest r WHERE r.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    List<LeaveRequest> findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(Collection<UUID> employeeUserIds, String status);

    List<LeaveRequest> findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Collection<UUID> employeeUserIds, String status, LocalDate to, LocalDate from);

    // Organization-wide equivalent of the above — no employeeUserId scoping. Backs the HR
    // dashboard's "On Leave" KPI (see LeaveService#listOrgLeave).
    List<LeaveRequest> findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            String status, LocalDate to, LocalDate from);

    // Organization-wide pending queue, no employeeUserId scoping — backs HR_ADMIN/SUPER_ADMIN
    // visibility in Approval Center (see LeaveService#listPendingApprovals's override branch).
    List<LeaveRequest> findByStatusOrderByCreatedAtAsc(String status);

    // Backs LeaveService#submitRequest's overlapping-request guard: true if the employee already
    // has a request in one of the given statuses (PENDING/APPROVED) whose date range overlaps the
    // new request's [startDate, endDate] (pass the new request's endDate as startDateAtOrBefore
    // and its startDate as endDateAtOrAfter — the standard range-overlap test). REJECTED is
    // deliberately excluded by the caller's status set, not by this query, so a rejected request
    // never blocks a new overlapping submission.
    boolean existsByEmployeeUserIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID employeeUserId, Collection<String> statuses, LocalDate startDateAtOrBefore, LocalDate endDateAtOrAfter);

    // Backs LeaveService#availableBalance: sums PENDING days across a set of leave-type IDs
    // within a calendar year for one employee, so the available balance excludes in-flight requests.
    @Query("SELECT COALESCE(SUM(r.totalDays), 0) FROM LeaveRequest r " +
           "WHERE r.employeeUserId = :employeeUserId " +
           "AND r.leaveType.id IN :leaveTypeIds " +
           "AND r.status = :status " +
           "AND r.startDate BETWEEN :startDate AND :endDate")
    BigDecimal sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
            @Param("employeeUserId") UUID employeeUserId,
            @Param("leaveTypeIds") Collection<UUID> leaveTypeIds,
            @Param("status") String status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
