package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class PayrollRunResponse {
    private UUID id;
    private UUID payGroupId;
    private LocalDate cycleStart;
    private LocalDate cycleEnd;
    private String status;
    private int employeeCount;
    private BigDecimal totalGross;
    private BigDecimal totalDeductions;
    private BigDecimal totalNet;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime disbursedAt;
    private LocalDateTime lastRolledBackAt;
    private String lastRollbackReason;
    // US-C09 — the snapshotted approval chain and which stage is currently pending; both null
    // before submission or once APPROVED.
    private List<String> approvalStages;
    private String pendingApprovalStage;
    private LocalDateTime createdAt;
}
