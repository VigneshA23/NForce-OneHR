package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PN-01: one row of the employee's own "My Penalties" view — either a persisted
 * {@code AttendancePenalty}, or a detected incident still inside the policy's buffer period that
 * may yet become one ({@code status = IN_BUFFER}, no deduction figures).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MyPenaltyResponse {

    private UUID id;
    private LocalDate incidentDate;
    /** The underlying {@code ExceptionType} constant — what the deep links key off. */
    private String discrepancyType;
    /** Employee-facing rule name: Late Arrival / Shortage / No Attendance / Missing Logs. */
    private String rule;
    /** Plain-language explanation, e.g. "Arrived 10:42, grace ended 10:15". */
    private String details;
    /** PENDING_REVIEW (applied, active), CANCELLED, REVERSED or IN_BUFFER. */
    private String status;
    private BigDecimal deductionDays;
    private BigDecimal leaveDeductionDays;
    private BigDecimal lopDays;
    private List<LeaveSplit> leaveBreakdown;
    private LocalDateTime penalizedOn;
    /** Last day (inclusive) on which the employee can still correct the day; null once it has passed / no buffer. */
    private LocalDate correctableUntil;
    /** True when Regularize / Apply Leave is still offered: inside the buffer period and no request already open. */
    private boolean correctable;
    /** A regularization for this date is already pending/approved — corrective actions are hidden. */
    private boolean regularizationOpen;
    private String cancellationReason;
    /** PN-02: the employee can still dispute this penalty (active, not yet disputed, payroll not locked). */
    private boolean disputable;
    /** PN-02: the dispute raised on this penalty, if any - its status is shown on the row. */
    private PenaltyDisputeResponse dispute;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LeaveSplit {
        private String leaveTypeCode;
        private String leaveTypeName;
        private BigDecimal days;
    }
}
