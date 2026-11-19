package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class StatutoryOverrideResponse {
    private UUID id;
    private UUID employeeId;
    private String country;
    private String ruleType;
    private LocalDate cycleStart;
    private LocalDate cycleEnd;
    private BigDecimal overrideAmount;
    private String overrideReason;
    private UUID overriddenBy;
    private LocalDateTime overriddenAt;
}
