package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {

    List<LeaveBalance> findByEmployeeUserIdAndYear(UUID employeeUserId, Integer year);

    List<LeaveBalance> findByEmployeeUserIdInAndYear(Collection<UUID> employeeUserIds, Integer year);

    Optional<LeaveBalance> findByEmployeeUserIdAndLeaveTypeIdAndYear(UUID employeeUserId, UUID leaveTypeId, Integer year);

    /** Balances of one type from a leave year onwards — "apply this entitlement change to existing balances". */
    List<LeaveBalance> findByLeaveTypeIdAndYearGreaterThanEqual(UUID leaveTypeId, Integer year);

    /** Year-end job input: every balance of one type for one leave year. */
    List<LeaveBalance> findByLeaveTypeIdAndYear(UUID leaveTypeId, Integer year);

    /** Expiry job input: carried-forward buckets whose expiry date has passed. */
    // Only buckets with days still neither used nor expired: once processed they drop out, so a
    // daily run stays cheap. (Days still claimed by pending leave dated before expiry keep the row in.)
    @Query("SELECT b FROM LeaveBalance b JOIN FETCH b.leaveType WHERE b.carriedForwardExpiresOn < :today "
            + "AND b.carriedForwardDays - b.carriedForwardUsedDays - b.carriedForwardExpiredDays > 0")
    List<LeaveBalance> findWithCarriedForwardExpiredBefore(@Param("today") java.time.LocalDate today);

    /**
     * Race-safe lazy creation (V310): two requests creating the same (employee, type, year) row at
     * once both succeed — the second insert is a no-op against the existing UNIQUE constraint.
     */
    @Modifying
    @Query(value = "INSERT INTO leave_balances (employee_user_id, leave_type_id, year, total_days, used_days) "
            + "VALUES (:employeeUserId, :leaveTypeId, :year, :totalDays, 0) "
            + "ON CONFLICT (employee_user_id, leave_type_id, year) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("employeeUserId") UUID employeeUserId, @Param("leaveTypeId") UUID leaveTypeId,
                       @Param("year") Integer year, @Param("totalDays") BigDecimal totalDays);
}
