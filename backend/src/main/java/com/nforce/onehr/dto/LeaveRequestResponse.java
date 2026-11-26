package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data @Builder
public class LeaveRequestResponse {
    private UUID id;
    private UUID employeeUserId;
    private String employeeName;
    private String employeeCode;
    private String leaveTypeCode;
    private String leaveTypeName;
    /** The request's type before every type was merged into Paid / Unpaid (V314), e.g. "Sick Leave"; null otherwise. */
    private String originalLeaveTypeName;
    private String leaveTypeClassification;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean halfDay;
    private String halfDaySession;
    private BigDecimal totalDays;
    /**
     * YE-03: leave year -> days, only for a request that spans two leave years (null otherwise). Each
     * portion is checked against and deducted from its own year's balance; the request stays one leave.
     */
    private Map<Integer, BigDecimal> daysByYear;
    private String status;
    private String employeeReason;
    private String decisionReason;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
}
