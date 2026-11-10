package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class PayrollProrationResponse {
    private UUID id;
    private UUID employeeId;
    private LocalDate cycleStart;
    private LocalDate cycleEnd;
    private int cycleDays;
    private int employedDays;
    private BigDecimal prorationFactor;
    private String prorationType;
    private BigDecimal leaveEncashmentDays;
    private LocalDateTime computedAt;
}
