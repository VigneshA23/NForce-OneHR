package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class LeaveBalanceResponse {
    /** Only populated by the team (My Team) listing and the admin view — null on the caller's own balances. */
    private UUID employeeUserId;
    /** Admin view only: the row a manual adjustment targets. */
    private UUID balanceId;
    private UUID leaveTypeId;
    private String leaveTypeCode;
    private String leaveTypeName;
    private String classification;
    /** ANNUAL, PER_OCCURRENCE, or UNLIMITED (unpaid). */
    private String entitlementBasis;
    /** Unpaid leave: no limit — every numeric field below is null. */
    private boolean unlimited;
    private Integer year;
    /** Current-year entitlement (ANNUAL) or the occurrence entitlement (PER_OCCURRENCE). */
    private BigDecimal totalDays;
    /** Used from the current-year entitlement plus used from the carried-forward bucket. */
    private BigDecimal usedDays;
    private BigDecimal pendingDays;
    /** What a new request can still draw on: current + usable carried-forward - used - pending. */
    private BigDecimal remainingDays;
    private BigDecimal carriedForwardDays;
    private BigDecimal carriedForwardUsedDays;
    private BigDecimal carriedForwardExpiredDays;
    private BigDecimal carriedForwardRemainingDays;
    private LocalDate carriedForwardExpiresOn;
    /** PER_OCCURRENCE: the current occurrence's window, null when none is open yet (full entitlement available). */
    private LocalDate occurrenceStartDate;
    private LocalDate occurrenceWindowEndDate;
}
