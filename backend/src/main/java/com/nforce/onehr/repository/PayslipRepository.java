package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Payslip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayslipRepository extends JpaRepository<Payslip, UUID> {

    @Query("SELECT p FROM Payslip p WHERE p.payrollRun.id = :runId ORDER BY p.employeeId")
    List<Payslip> findByPayrollRunId(@Param("runId") UUID runId);

    Optional<Payslip> findByPayrollRunIdAndEmployeeId(UUID payrollRunId, UUID employeeId);

    // US-C12 — employee-facing reads. The run status filter is part of the query itself (not a
    // post-filter) so a DRAFT/SUBMITTED run's payslip can never be returned by any caller.
    @Query("SELECT p FROM Payslip p JOIN FETCH p.payrollRun r WHERE p.employeeId = :employeeId "
         + "AND r.status IN :statuses ORDER BY r.cycleEnd DESC")
    List<Payslip> findVisibleForEmployee(@Param("employeeId") UUID employeeId,
                                         @Param("statuses") Collection<String> statuses);

    // US-C17 YTD — one row: [payslip count, SUM gross, SUM deductions, SUM net, SUM LOP days]. The sums are
    // null when there are no matching payslips. A payslip belongs to the fiscal year its pay cycle ENDS in.
    @Query("SELECT COUNT(p), SUM(p.grossPay), SUM(p.totalDeductions), SUM(p.netPay), SUM(p.lopDays) "
         + "FROM Payslip p WHERE p.employeeId = :employeeId AND p.payrollRun.cycleEnd >= :from "
         + "AND p.payrollRun.cycleEnd <= :to AND p.payrollRun.status IN :statuses")
    List<Object[]> summarizeForEmployee(@Param("employeeId") UUID employeeId,
                                        @Param("from") LocalDate from, @Param("to") LocalDate to,
                                        @Param("statuses") Collection<String> statuses);

    @Query("SELECT p FROM Payslip p JOIN FETCH p.payrollRun r WHERE p.employeeId = :employeeId "
         + "AND r.cycleEnd >= :from AND r.cycleEnd <= :to AND r.status IN :statuses ORDER BY r.cycleEnd DESC")
    List<Payslip> findVisibleForEmployeeBetween(@Param("employeeId") UUID employeeId,
                                                @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                @Param("statuses") Collection<String> statuses);

    // Explicit bulk @Modifying delete, not a derived deleteBy... method — a derived delete loads
    // entities and defers their removal to Hibernate's next flush, and Hibernate's action queue
    // always runs pending INSERTs before pending DELETEs in the same flush. That ordering made
    // PayrollRunService's regenerate-while-DRAFT path (delete old payslips, then insert new ones
    // for the same employee) hit uq_payslips_run_employee on the still-undeleted old row — caught
    // live, not hypothetical. This bulk query executes immediately instead.
    @Modifying
    @Query("DELETE FROM Payslip p WHERE p.payrollRun.id = :runId")
    void deleteByPayrollRunId(@Param("runId") UUID runId);

    // US-C27 — same bulk-delete reasoning as deleteByPayrollRunId above, scoped to one employee:
    // PayrollRunService#rollbackEmployeePayslip deletes this employee's old payslip and inserts a
    // freshly recomputed one in the same transaction, so a derived delete's deferred-to-flush
    // removal would hit the exact same uq_payslips_run_employee ordering bug.
    @Modifying
    @Query("DELETE FROM Payslip p WHERE p.payrollRun.id = :runId AND p.employeeId = :employeeId")
    void deleteByPayrollRunIdAndEmployeeId(@Param("runId") UUID runId, @Param("employeeId") UUID employeeId);
}
