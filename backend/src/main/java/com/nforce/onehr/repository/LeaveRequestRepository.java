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

    // Sum of totalDays across an employee's requests of one status, across one or more leave
    // types, whose startDate falls within a calendar year — backs status-aware balance: PENDING
    // requests reserve balance the same way APPROVED ones do, so a second submission can't
    // collectively exceed the quota (see LeaveService#availableBalance, the single calculation
    // shared by submission validation and the balance API/pie chart). Takes a collection of
    // leave-type IDs (not just one) so the consolidated Annual/Sick/Casual balance group can sum
    // reservations across all three types at once — a single type is just a one-element list.
    @Query("SELECT COALESCE(SUM(r.totalDays), 0) FROM LeaveRequest r " +
           "WHERE r.employeeUserId = :employeeUserId AND r.leaveType.id IN :leaveTypeIds " +
           "AND r.status = :status AND r.startDate BETWEEN :from AND :to")
    BigDecimal sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
            @Param("employeeUserId") UUID employeeUserId,
            @Param("leaveTypeIds") Collection<UUID> leaveTypeIds,
            @Param("status") String status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
