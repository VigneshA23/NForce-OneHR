package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class ArrearsResponse {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private BigDecimal amount;
    private String reason;
    private String sourceType;
    private LocalDate sourceCycleStart;
    private LocalDate sourceCycleEnd;
    private String status;
    private UUID appliedToRunId;
    private LocalDateTime createdAt;
}
