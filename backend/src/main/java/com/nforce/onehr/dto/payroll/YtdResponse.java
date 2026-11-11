package com.nforce.onehr.dto.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * US-C17 — one employee's cumulative payroll figures for one fiscal year, built only from payslips
 * of APPROVED/DISBURSED runs. {@code payslipCount} is how many processed pay cycles it covers.
 */
public record YtdResponse(UUID employeeId, String fiscalYear, LocalDate fiscalYearStart, LocalDate fiscalYearEnd,
                          int payslipCount, BigDecimal grossPay, BigDecimal totalDeductions, BigDecimal netPay,
                          BigDecimal lopDays, List<Component> earnings, List<Component> deductions) {

    public record Component(String name, BigDecimal amount) {}
}
