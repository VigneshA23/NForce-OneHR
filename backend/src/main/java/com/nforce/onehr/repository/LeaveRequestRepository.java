package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
