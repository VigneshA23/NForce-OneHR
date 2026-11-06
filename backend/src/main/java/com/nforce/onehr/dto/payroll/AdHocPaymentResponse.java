package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class AdHocPaymentResponse {
    private UUID id;
    private UUID payrollRunId;
    private UUID employeeId;
    private UUID componentId;
    private String componentName;
    private String componentType;
    private BigDecimal amount;
    private String reason;
    private String addedByName;
    private LocalDateTime addedAt;
}
