package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class AdHocDeductionResponse {
    private UUID id;
    private UUID employeeId;
    private UUID componentId;
    private String componentName;
    private String componentType;
    private BigDecimal totalAmount;
    private BigDecimal installmentAmount;
    private BigDecimal remainingBalance;
    private String status;
    private String reason;
    private String createdByName;
    private LocalDateTime createdAt;
}
