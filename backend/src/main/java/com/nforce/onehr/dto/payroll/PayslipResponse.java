package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class PayslipResponse {
    private UUID id;
    private UUID payrollRunId;
    private UUID employeeId;
    private BigDecimal ctc;
    private int cycleDays;
    private BigDecimal lopDays;
    private int employedDays;
    private BigDecimal prorationFactor;
    private BigDecimal grossPay;
    private BigDecimal totalDeductions;
    private BigDecimal netPay;
    private boolean released;
    private List<PayslipLineItemResponse> lineItems;
}
