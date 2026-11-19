package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayslipLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface PayslipLineItemRepository extends JpaRepository<PayslipLineItem, UUID> {

    List<PayslipLineItem> findByPayslipId(UUID payslipId);

    // Rewritten from `li.payslip.payrollRun.id = :runId` (2026-11-19): that 2-hop association path
    // can't be a direct single-table DELETE, so Hibernate translated it into a correlated
    // `EXISTS(SELECT 1 FROM (VALUES(0)) d_ JOIN payslips ...)` subquery per PayslipLineItem row —
    // confirmed via a live Postgres `statement timeout` on exactly this query during a regenerate,
    // on an endpoint whose own config already documents being latency-sensitive. An IN-subquery
    // against the handful of this run's own payslip ids is the standard, index-friendly shape.
    @Modifying
    @Query("DELETE FROM PayslipLineItem li WHERE li.payslip.id IN (SELECT p.id FROM Payslip p WHERE p.payrollRun.id = :runId)")
    void deleteByPayrollRunId(@Param("runId") UUID runId);

    // US-C17 YTD — [component name, component type, SUM(amount)] per component for one employee over the
    // runs whose pay cycle ends inside [from, to], filtered to the given run statuses in the query itself.
    @Query("SELECT li.componentName, li.componentType, SUM(li.amount) FROM PayslipLineItem li "
         + "WHERE li.payslip.employeeId = :employeeId AND li.payslip.payrollRun.cycleEnd >= :from "
         + "AND li.payslip.payrollRun.cycleEnd <= :to AND li.payslip.payrollRun.status IN :statuses "
         + "GROUP BY li.componentName, li.componentType")
    List<Object[]> sumByComponentForEmployee(@Param("employeeId") UUID employeeId,
                                             @Param("from") java.time.LocalDate from,
                                             @Param("to") java.time.LocalDate to,
                                             @Param("statuses") java.util.Collection<String> statuses);

    // US-C27 — scoped version for PayrollRunService#rollbackEmployeePayslip, same bulk-delete
    // reasoning as deleteByPayrollRunId.
    // Same IN-subquery rewrite as deleteByPayrollRunId above, scoped to one employee.
    @Modifying
    @Query("DELETE FROM PayslipLineItem li WHERE li.payslip.id IN "
         + "(SELECT p.id FROM Payslip p WHERE p.payrollRun.id = :runId AND p.employeeId = :employeeId)")
    void deleteByPayrollRunIdAndEmployeeId(@Param("runId") UUID runId, @Param("employeeId") UUID employeeId);

    // US-C10 reconciliation — SUM(amount) per component type across every line item in the run,
    // compared against PayrollRun's own stored totals.
    @Query("SELECT li.componentType, SUM(li.amount) FROM PayslipLineItem li "
         + "WHERE li.payslip.payrollRun.id = :runId GROUP BY li.componentType")
    List<Object[]> sumAmountByComponentType(@Param("runId") UUID runId);

    // Pay Register export (PayrollRunService#payRegisterExcel) — every line item in the run as a
    // flat [employeeId, componentId, componentName, componentType, amount] projection rather than
    // loading Payslip entities, since this only ever reads employeeId off of them.
    @Query("SELECT li.payslip.employeeId, li.componentId, li.componentName, li.componentType, li.amount " +
         "FROM PayslipLineItem li WHERE li.payslip.payrollRun.id = :runId")
    List<Object[]> findRowsByPayrollRunId(@Param("runId") UUID runId);
}
