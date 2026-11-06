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
public class PayrollLopCalculationResponse {
    private UUID id;
    private UUID employeeId;
    private LocalDate cycleStart;
    private LocalDate cycleEnd;
    private BigDecimal unpaidLeaveDays;
    private BigDecimal penaltyLopDays;
    private BigDecimal autoLopNoAttendanceDays;
    private BigDecimal computedLopDays;
    private List<LocalDate> needsManualReviewDates;
    private LocalDateTime computedAt;
    private BigDecimal overrideLopDays;
    private String overrideReason;
    private UUID overriddenBy;
    private LocalDateTime overriddenAt;
    /** overrideLopDays if set, else computedLopDays — what payroll would actually use. */
    private BigDecimal effectiveLopDays;
}
