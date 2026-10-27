package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data @Builder
public class LeaveBalanceResponse {
    /** Only populated by the team (My Team) listing — null on the caller's own balances. */
    private UUID employeeUserId;
    private String leaveTypeCode;
    private String leaveTypeName;
    private Integer year;
    private BigDecimal totalDays;
    private BigDecimal usedDays;
    private BigDecimal remainingDays;
}
