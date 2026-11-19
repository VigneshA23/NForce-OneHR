package com.nforce.onehr.dto.payroll;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Keka's "New Joinees & Exits" step — who joined or left within this run's own cycle window,
 * among the employees this run actually includes (via its payslips, same scoping
 * PayrollCostReportService already uses). Purely informational — nothing here feeds payroll math;
 * joiner/exit proration is already handled by PayrollProrationService at generation time. */
public record PayrollJoinersExitsResponse(UUID runId, LocalDate cycleStart, LocalDate cycleEnd,
                                          List<EmployeeDateEntry> joiners, List<EmployeeDateEntry> exits) {

    public record EmployeeDateEntry(UUID employeeId, String employeeName, LocalDate date) {}
}
