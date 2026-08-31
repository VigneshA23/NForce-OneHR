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

    // JOIN FETCH r.leaveType so LeaveService#listMyRequests's mapping of every row through
    // toRequestResponse (which reads r.getLeaveType().getCode()/getName()) doesn't lazily
    // hit the DB once per row — leaveType is @ManyToOne(LAZY) (see LeaveRequest.java).
    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.leaveType WHERE r.employeeUserId = :employeeUserId "
            + "ORDER BY r.createdAt DESC")
    List<LeaveRequest> findByEmployeeUserIdOrderByCreatedAtDesc(@Param("employeeUserId") UUID employeeUserId);

    // Backs audit-log target search — resolves which leave requests belong to a set of employees.
    @Query("SELECT r.id FROM LeaveRequest r WHERE r.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    // JOIN FETCH r.leaveType — backs LeaveService#listPendingApprovals's non-override branch,
    // same N+1 rationale as findByEmployeeUserIdOrderByCreatedAtDesc above.
    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.leaveType WHERE r.employeeUserId IN :employeeUserIds "
            + "AND r.status = :status ORDER BY r.createdAt ASC")
    List<LeaveRequest> findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(
            @Param("employeeUserIds") Collection<UUID> employeeUserIds, @Param("status") String status);

    // JOIN FETCH r.leaveType — backs LeaveService#listTeamLeave and #listPeerLeave, same N+1
    // rationale as findByEmployeeUserIdOrderByCreatedAtDesc above.
    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.leaveType WHERE r.employeeUserId IN :employeeUserIds "
            + "AND r.status = :status AND r.startDate <= :to AND r.endDate >= :from")
    List<LeaveRequest> findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            @Param("employeeUserIds") Collection<UUID> employeeUserIds, @Param("status") String status,
            @Param("to") LocalDate to, @Param("from") LocalDate from);

    // Organization-wide equivalent of the above — no employeeUserId scoping. Backs the HR
    // dashboard's "On Leave" KPI (see LeaveService#listOrgLeave). JOIN FETCH r.leaveType for the
    // same N+1 reason as the other list-backing queries in this file.
    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.leaveType WHERE r.status = :status "
            + "AND r.startDate <= :to AND r.endDate >= :from")
    List<LeaveRequest> findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            @Param("status") String status, @Param("to") LocalDate to, @Param("from") LocalDate from);

    // Organization-wide pending queue, no employeeUserId scoping — backs HR_ADMIN/SUPER_ADMIN
    // visibility in Approval Center (see LeaveService#listPendingApprovals's override branch).
    // JOIN FETCH r.leaveType for the same N+1 reason as the other list-backing queries in this file.
    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.leaveType WHERE r.status = :status ORDER BY r.createdAt ASC")
    List<LeaveRequest> findByStatusOrderByCreatedAtAsc(@Param("status") String status);

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

    // Backs ExpectedWorkHoursService#loadPartialHourLeaveByEmployeeDate: approved HOURLY/
    // QUARTER_DAY leave overlapping [from, to] for a set of employees.
    List<LeaveRequest> findByEmployeeUserIdInAndStatusAndDurationTypeInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Collection<UUID> employeeUserIds, String status, Collection<String> durationTypes, LocalDate to, LocalDate from);
}
