package com.nforce.onehr.dto.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Keka's "Salary Component Claim"/"Expenses" steps — read-only visibility into which expense
 * claims HR has already routed "with payroll" (ExpenseClaim.status = CLEARED_FOR_PAYROLL), for the
 * employees this run includes. Genuinely separate systems today — see PayrollClaimService's own
 * Javadoc for why this stays read-only rather than becoming a new payslip line item source. */
public record PayrollClaimResponse(UUID id, UUID employeeId, String employeeName, String categoryName,
                                   BigDecimal amount, LocalDate expenseDate, String status) {
}
