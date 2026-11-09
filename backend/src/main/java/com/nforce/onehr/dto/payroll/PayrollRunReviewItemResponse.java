package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class PayrollRunReviewItemResponse {
    private UUID id;
    private UUID payrollRunId;
    private UUID employeeId;
    private String employeeName;
    private String stepType;
    private String sourceType;
    private UUID sourceId;
    private LocalDate itemDate;
    private BigDecimal days;
    private String leaveTypeName;
    private String decision;
    private UUID selectedLeaveTypeId;
    private String comment;
    private String decidedByName;
    private LocalDateTime decidedAt;
}
