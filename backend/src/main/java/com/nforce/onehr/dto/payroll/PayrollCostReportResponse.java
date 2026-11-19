package com.nforce.onehr.dto.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * US-C16 — one payroll run's cost, broken down by department and by project. Built only from a
 * run whose status is APPROVED or DISBURSED (same convention as {@code PayslipService}/{@code
 * PayrollYtdService} — a DRAFT/SUBMITTED run's figures can still change on regenerate, so they're
 * never surfaced as a "cost").
 *
 * <p>{@code byProject} always includes an "Unallocated" row ({@code projectId} null) for whatever
 * share of pay isn't covered by a {@link com.nforce.onehr.entity.ProjectAllocation} overlapping
 * this run's cycle — this is how the report "degrades gracefully" when Epic B project-allocation
 * data doesn't exist for an employee (or at all): the whole run still totals correctly, it's just
 * entirely Unallocated.
 */
public record PayrollCostReportResponse(UUID runId, LocalDate cycleStart, LocalDate cycleEnd, int employeeCount,
                                        BigDecimal grossPay, BigDecimal totalDeductions, BigDecimal netPay,
                                        List<DepartmentCost> byDepartment, List<ProjectCost> byProject) {

    public record DepartmentCost(UUID departmentId, String departmentName, int employeeCount,
                                 BigDecimal grossPay, BigDecimal totalDeductions, BigDecimal netPay) {}

    /** {@code projectId} null means the "Unallocated" bucket, not a real project. */
    public record ProjectCost(UUID projectId, String projectCode, String projectName,
                              BigDecimal grossPay, BigDecimal totalDeductions, BigDecimal netPay) {}
}
