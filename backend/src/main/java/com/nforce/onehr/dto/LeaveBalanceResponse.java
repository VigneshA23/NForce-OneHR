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
    /** False for a deactivated type: only the admin view lists those (so HR can still correct them). */
    @Builder.Default
    private boolean leaveTypeActive = true;
    private String classification;
    /** ANNUAL, PER_OCCURRENCE, or UNLIMITED (unpaid). */
    private String entitlementBasis;
    /** Unpaid leave: no limit — every numeric field below is null. */
    private boolean unlimited;
    private Integer year;
    /**
     * The leave year hasn't started yet: nothing is credited, and the figures are what the employee is
     * expected to receive under the policy in force ({@code annualQuotaDays}), less leave already requested
     * in that year. Read-only — viewing a future year never creates a balance row.
     */
    private boolean expected;
    /** Days credited so far this year (ANNUAL) or the occurrence entitlement (PER_OCCURRENCE). */
    private BigDecimal totalDays;
    /** Used from the current-year entitlement plus used from the carried-forward bucket. */
    private BigDecimal usedDays;
    private BigDecimal pendingDays;
    /** Balance from credited days: current + usable carried-forward - used - pending. Negative = days taken in advance. */
    private BigDecimal remainingDays;
    private BigDecimal carriedForwardDays;
    private BigDecimal carriedForwardUsedDays;
    private BigDecimal carriedForwardExpiredDays;
    private BigDecimal carriedForwardRemainingDays;
    private LocalDate carriedForwardExpiresOn;
    // ── Crediting (V314, ANNUAL only) ──
    /** YEARLY (all at once), HALF_YEARLY, QUARTERLY or MONTHLY (LV-03). */
    private String creditFrequency;
    /** This year's full entitlement for this employee: credited so far plus credits still to come. */
    private BigDecimal annualQuotaDays;
    /** Credits still to come this year (what may be taken in advance). */
    private BigDecimal upcomingCreditDays;
    /** What a new request may use including advance: remaining + upcoming credits. Can exceed remainingDays. */
    private BigDecimal availableToApplyDays;
    private LocalDate nextCreditDate;
    private BigDecimal nextCreditDays;

    /** PER_OCCURRENCE: the current occurrence's window, null when none is open yet (full entitlement available). */
    private LocalDate occurrenceStartDate;
    private LocalDate occurrenceWindowEndDate;
}
