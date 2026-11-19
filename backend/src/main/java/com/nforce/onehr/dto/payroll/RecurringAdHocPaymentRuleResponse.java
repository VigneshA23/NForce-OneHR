package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class RecurringAdHocPaymentRuleResponse {
    private UUID id;
    private UUID payGroupId;
    private String payGroupName;
    private String componentName;
    private String componentType;
    private BigDecimal amount;
    private String reason;
    private boolean active;
    private String createdByName;
    private LocalDateTime createdAt;
}
