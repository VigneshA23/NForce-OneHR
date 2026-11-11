package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayrollRunReviewItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface PayrollRunReviewItemRepository extends JpaRepository<PayrollRunReviewItem, UUID> {

    @Query("SELECT i FROM PayrollRunReviewItem i WHERE i.payrollRun.id = :runId AND i.stepType = :stepType "
            + "ORDER BY i.employeeId, i.itemDate")
    List<PayrollRunReviewItem> findByPayrollRunIdAndStepType(@Param("runId") UUID runId, @Param("stepType") String stepType);

    boolean existsByPayrollRunIdAndStepTypeAndDecision(UUID payrollRunId, String stepType, String decision);

    // Excludes a rejected LEAVE_UNPAID item's underlying LeaveRequest from
    // PayrollLopCalculationService's unpaid-leave sum — see PayrollRunReviewItem's own Javadoc for
    // why this exclusion isn't durable across a regenerate (unlike a rejected PENALTY item).
    @Query("SELECT i.sourceId FROM PayrollRunReviewItem i WHERE i.payrollRun.id = :runId AND i.employeeId = :employeeId "
            + "AND i.sourceType = 'LEAVE_UNPAID' AND i.decision = 'REJECTED'")
    Set<UUID> findRejectedLeaveRequestIds(@Param("runId") UUID runId, @Param("employeeId") UUID employeeId);

    // How many of this employee's NO_ATTENDANCE items this run were explicitly marked LOP — each
    // adds one day to PayrollLopCalculationService's computed LOP, on top of the existing
    // no-available-balance auto-LOP shortcut (untouched by this story).
    @Query("SELECT COUNT(i) FROM PayrollRunReviewItem i WHERE i.payrollRun.id = :runId AND i.employeeId = :employeeId "
            + "AND i.stepType = 'NO_ATTENDANCE' AND i.decision = 'MARK_LOP'")
    long countMarkLopDecisions(@Param("runId") UUID runId, @Param("employeeId") UUID employeeId);

    // Explicit bulk @Modifying delete, not a derived deleteBy... method — same reasoning
    // documented on PayslipRepository#deleteByPayrollRunId: these rows are deleted and recreated
    // in the same transaction (see PayrollRunReviewService#generateItemsForEmployee and
    // #deleteItemsForEmployee), and a derived delete's deferred-to-flush removal would risk the
    // same insert-before-delete ordering bug. See PayrollRunReviewService's own Javadoc for why
    // deleting an employee's OLD items has to happen AFTER
    // PayrollLopCalculationService#recomputeWithDetails has already read them (for a rejected
    // leave request or a MARK_LOP decision), not before — deleting up front, same as payslips,
    // would wipe a decision before it ever gets a chance to take effect.
    @Modifying
    @Query("DELETE FROM PayrollRunReviewItem i WHERE i.payrollRun.id = :runId AND i.employeeId = :employeeId")
    void deleteByPayrollRunIdAndEmployeeId(@Param("runId") UUID runId, @Param("employeeId") UUID employeeId);

    List<PayrollRunReviewItem> findByIdIn(List<UUID> ids);
}
