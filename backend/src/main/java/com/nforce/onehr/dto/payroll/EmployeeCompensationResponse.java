package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class EmployeeCompensationResponse {
    private UUID id;
    private UUID employeeId;
    private UUID payGroupId;
    private UUID structureId;
    private String structureName;
    private BigDecimal ctc;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private LocalDateTime createdAt;
}
