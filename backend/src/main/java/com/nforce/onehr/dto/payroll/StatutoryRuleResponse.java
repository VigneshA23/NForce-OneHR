package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class StatutoryRuleResponse {
    private UUID id;
    private String country;
    private String ruleType;
    private String slabConfig;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private LocalDateTime createdAt;
}
