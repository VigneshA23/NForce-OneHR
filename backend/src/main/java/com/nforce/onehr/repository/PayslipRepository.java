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

    // PN-02: "payroll lock" for a penalty dispute - the employee has a payslip in an APPROVED or
    // DISBURSED run (the codebase's locked statuses, see PayrollRunRepository#findLaterLockedRuns)
    // whose cycle contains the incident date. Payroll counts penalties by incidentDate in the cycle.
    @Query("SELECT COUNT(p) > 0 FROM Payslip p JOIN p.payrollRun r WHERE p.employeeId = :employeeId "
         + "AND r.status IN ('APPROVED', 'DISBURSED') AND r.cycleStart <= :date AND r.cycleEnd >= :date")
    boolean existsLockedPayslipCovering(@Param("employeeId") UUID employeeId, @Param("date") java.time.LocalDate date);

    // Status-only — deliberately NOT gated on released. Shared by internal business logic that
    // must see every closed-cycle payslip regardless of distribution state (EmployeeCompensationService
    // #raiseArrearsForBackdatedRevision, PayrollLopCalculationService#raiseArrearsIfCycleAlreadyClosed)
    // as well as PayslipService#renderForEmployee's admin-view path (HR must be able to view/email/
    // zip a payslip BEFORE deciding to distribute it — that decision is the whole point of
    // PayrollRunService#distributePayslips). True employee self-service reads use
    // findReleasedForEmployee/findReleasedForEmployeeBetween below instead.
    @Query("SELECT p FROM Payslip p JOIN FETCH p.payrollRun r WHERE p.employeeId = :employeeId "
         + "AND r.status IN :statuses ORDER BY r.cycleEnd DESC")
    List<Payslip> findVisibleForEmployee(@Param("employeeId") UUID employeeId,
                                         @Param("statuses") Collection<String> statuses);

    // US-C12 employee self-service only — adds released=true on top of findVisibleForEmployee's
    // own status filter, so an employee can never see a payslip HR hasn't distributed to them yet
    // (see PayrollRunService#distributePayslips). Never used for internal business-logic lookups
    // (those use findVisibleForEmployee, unfiltered on released) or for HR's own admin-view path.
    @Query("SELECT p FROM Payslip p JOIN FETCH p.payrollRun r WHERE p.employeeId = :employeeId "
         + "AND r.status IN :statuses AND p.released = true ORDER BY r.cycleEnd DESC")
    List<Payslip> findReleasedForEmployee(@Param("employeeId") UUID employeeId,
                                          @Param("statuses") Collection<String> statuses);

    // US-C17 YTD (self-service only, GET /api/me/finances/ytd) — one row: [payslip count, SUM gross,
    // SUM deductions, SUM net, SUM LOP days]. The sums are null when there are no matching payslips.
    // A payslip belongs to the fiscal year its pay cycle ENDS in. released=true for the same reason
    // as findReleasedForEmployee — an employee's own YTD total must never include a payslip HR
    // hasn't distributed to them yet.
    @Query("SELECT COUNT(p), SUM(p.grossPay), SUM(p.totalDeductions), SUM(p.netPay), SUM(p.lopDays) "
         + "FROM Payslip p WHERE p.employeeId = :employeeId AND p.payrollRun.cycleEnd >= :from "
         + "AND p.payrollRun.cycleEnd <= :to AND p.payrollRun.status IN :statuses AND p.released = true")
    List<Object[]> summarizeForEmployee(@Param("employeeId") UUID employeeId,
                                        @Param("from") LocalDate from, @Param("to") LocalDate to,
                                        @Param("statuses") Collection<String> statuses);

    // Status-only — see findVisibleForEmployee's own doc above for why this stays ungated on
    // released (shared with internal arrears lookups and HR's admin-view path).
    @Query("SELECT p FROM Payslip p JOIN FETCH p.payrollRun r WHERE p.employeeId = :employeeId "
         + "AND r.cycleEnd >= :from AND r.cycleEnd <= :to AND r.status IN :statuses ORDER BY r.cycleEnd DESC")
    List<Payslip> findVisibleForEmployeeBetween(@Param("employeeId") UUID employeeId,
                                                @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                @Param("statuses") Collection<String> statuses);

    // Employee self-service only (PayslipService#renderForEmployee's self-view branch) — same
    // released=true addition as findReleasedForEmployee.
    @Query("SELECT p FROM Payslip p JOIN FETCH p.payrollRun r WHERE p.employeeId = :employeeId "
         + "AND r.cycleEnd >= :from AND r.cycleEnd <= :to AND r.status IN :statuses AND p.released = true ORDER BY r.cycleEnd DESC")
    List<Payslip> findReleasedForEmployeeBetween(@Param("employeeId") UUID employeeId,
                                                 @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                 @Param("statuses") Collection<String> statuses);

    // Centralized distribution (PayrollRunService#distributePayslips) — "Publish to Employee
    // Portal" for every payslip in the run, or just the given employees. Explicit bulk @Modifying
    // update, not load-then-save, for the same reason the bulk deletes below are: this can cover
    // every payslip in a run in one round trip.
    @Modifying
    @Query("UPDATE Payslip p SET p.released = true WHERE p.payrollRun.id = :runId")
    void releaseByPayrollRunId(@Param("runId") UUID runId);

    @Modifying
    @Query("UPDATE Payslip p SET p.released = true WHERE p.payrollRun.id = :runId AND p.employeeId IN :employeeIds")
    void releaseByPayrollRunIdAndEmployeeIdIn(@Param("runId") UUID runId, @Param("employeeIds") Collection<UUID> employeeIds);

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
