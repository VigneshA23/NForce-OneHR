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

    @Modifying
    @Query("DELETE FROM PayslipLineItem li WHERE li.payslip.payrollRun.id = :runId")
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
    @Modifying
    @Query("DELETE FROM PayslipLineItem li WHERE li.payslip.payrollRun.id = :runId AND li.payslip.employeeId = :employeeId")
    void deleteByPayrollRunIdAndEmployeeId(@Param("runId") UUID runId, @Param("employeeId") UUID employeeId);

    // US-C10 reconciliation — SUM(amount) per component type across every line item in the run,
    // compared against PayrollRun's own stored totals.
    @Query("SELECT li.componentType, SUM(li.amount) FROM PayslipLineItem li "
         + "WHERE li.payslip.payrollRun.id = :runId GROUP BY li.componentType")
    List<Object[]> sumAmountByComponentType(@Param("runId") UUID runId);
}
