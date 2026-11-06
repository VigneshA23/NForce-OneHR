package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class AdHocDeductionInstallmentResponse {
    private UUID id;
    private UUID payrollRunId;
    private BigDecimal amount;
    private LocalDateTime appliedAt;
}
