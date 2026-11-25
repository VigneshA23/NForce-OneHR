package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveAccrualCredit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface LeaveAccrualCreditRepository extends JpaRepository<LeaveAccrualCredit, UUID> {

    List<LeaveAccrualCredit> findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(UUID employeeUserId, UUID leaveTypeId, Integer year);

    /** [employeeUserId, month] of every credited month of one type and year: the nightly run's "already done" set. */
    @Query("SELECT c.employeeUserId, c.month FROM LeaveAccrualCredit c WHERE c.leaveTypeId = :typeId AND c.year = :year")
    List<Object[]> findCreditedMonths(@Param("typeId") UUID typeId, @Param("year") Integer year);

    /**
     * Race-safe credit: 1 when this call created the month's row, 0 when it already existed (another
     * run or instance got there first). Only a 1 may be added to the balance.
     */
    @Modifying
    @Query(value = "INSERT INTO leave_accrual_credits (id, employee_user_id, leave_type_id, year, month, days, source, credited_at, updated_at) "
            + "VALUES (:id, :employeeUserId, :leaveTypeId, :year, :month, :days, 'ACCRUAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (employee_user_id, leave_type_id, year, month) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("employeeUserId") UUID employeeUserId, @Param("leaveTypeId") UUID leaveTypeId,
                       @Param("year") Integer year, @Param("month") Integer month, @Param("days") BigDecimal days);
}
