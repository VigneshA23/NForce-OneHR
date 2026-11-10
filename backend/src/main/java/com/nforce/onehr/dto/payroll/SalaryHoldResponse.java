package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class SalaryHoldResponse {
    private UUID id;
    private UUID payrollRunId;
    private UUID employeeId;
    private String reason;
    private String holdType;
    private String status;
    private String createdByName;
    private LocalDateTime createdAt;
    private String releasedByName;
    private LocalDateTime releasedAt;
}
