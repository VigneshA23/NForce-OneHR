package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One employee whose net pay changed vs the previous finalized cycle, plus a best-effort guess at
 *  why — see PayrollRunService#driversFor for exactly what each driver tag means and its limits. */
@Getter
@Builder
public class EmployeeVarianceResponse {
    private UUID employeeId;
    private String employeeName;
    private BigDecimal previousNetPay;
    private BigDecimal currentNetPay;
    private BigDecimal delta;
    private List<String> drivers;
}
