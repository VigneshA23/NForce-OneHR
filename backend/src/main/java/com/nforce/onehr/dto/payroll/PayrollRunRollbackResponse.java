package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class PayrollRunRollbackResponse {
    private UUID id;
    private String reason;
    private UUID rolledBackBy;
    private LocalDateTime rolledBackAt;
}
