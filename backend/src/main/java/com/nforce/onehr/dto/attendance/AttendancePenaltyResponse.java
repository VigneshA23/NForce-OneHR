package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One row of the Regularize & Cancel Penalties table. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendancePenaltyResponse {

    private UUID id;
    private UUID employeeUserId;
    private String fullName;
    private String employeeCode;
    private LocalDate incidentDate;
    private LocalDateTime penalizedOn;
    private String status;
    private String locationName;
    private String departmentName;
    private String discrepancyType;
    // The matched Penalization Policy rule's configured deduction amount at evaluation time —
    // null for discrepancy types with no configured amount (e.g. no matching Work Hours tier).
    private BigDecimal deductionDays;
    // True only when status is PENDING_REVIEW or APPLIED — CANCELLED/REVERSED are terminal.
    // Rows with an active regularization are excluded from the list entirely (see
    // AttendancePenaltyService), so every row returned here is, by construction, not blocked
    // by one.
    private boolean cancellable;
}
