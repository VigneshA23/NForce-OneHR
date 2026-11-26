package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** PN-02: one dispute - shown on the employee's My Penalties row and in the manager/HR queue. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenaltyDisputeResponse {
    private UUID id;
    private UUID penaltyId;
    private UUID employeeUserId;
    private String employeeName;
    private String employeeCode;
    private LocalDate incidentDate;
    private String rule;
    private BigDecimal deductionDays;
    private String reason;
    private String evidence;
    /** PENDING_MANAGER, PENDING_HR, UPHELD or REVERSED. */
    private String status;
    private String managerRecommendation;
    private String managerComment;
    private LocalDateTime managerActedAt;
    private String resolutionComment;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
}
