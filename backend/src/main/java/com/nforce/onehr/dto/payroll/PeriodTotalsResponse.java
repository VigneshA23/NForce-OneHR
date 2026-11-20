package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One cycle's headline totals — used twice in {@link PreApprovalSummaryResponse}: once for the
 *  run being reviewed, once (nullable at the call site, never here) for the previous finalized
 *  cycle it's being compared against. */
@Getter
@Builder
public class PeriodTotalsResponse {
    private BigDecimal grossPay;
    private BigDecimal totalDeductions;
    private BigDecimal netPay;
    private BigDecimal employerContributions;
    private int employeeCount;
}
