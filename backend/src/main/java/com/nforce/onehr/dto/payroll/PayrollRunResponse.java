package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class PayrollRunResponse {
    private UUID id;
    private UUID payGroupId;
    private LocalDate cycleStart;
    private LocalDate cycleEnd;
    private String status;
    private int employeeCount;
    private BigDecimal totalGross;
    private BigDecimal totalDeductions;
    private BigDecimal totalNet;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime disbursedAt;
    private LocalDateTime lastRolledBackAt;
    private String lastRollbackReason;
    private LocalDateTime createdAt;
}
