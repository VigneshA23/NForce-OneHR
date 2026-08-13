package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Unified approval queue item returned by the Approval Center endpoint.
 * All approve/reject decisions for EVERY request type happen ONLY via Approval Center —
 * no other page may issue approval decisions.
 *
 * requestType discriminator: LEAVE | REGULARIZATION | WEB_CLOCK_IN | EXPENSE | ASSET_REQUEST | HELP_CONTENT
 */
@Data
@Builder
public class ApprovalItemDto {

    private String id;          // String to cover UUID (Leave/Expense) and Long (Regularization/AssetRequest)
    private String requestType; // LEAVE | REGULARIZATION | WEB_CLOCK_IN | EXPENSE | ASSET_REQUEST
    private UUID employeeUserId;
    private String employeeName;
    private Instant createdAt;

    // ── Leave ────────────────────────────────────────────
    private String leaveTypeName;
    private LocalDate leaveStartDate;
    private LocalDate leaveEndDate;
    private BigDecimal leaveTotalDays;
    private Boolean leaveHalfDay;
    private String leaveReason;

    // ── Attendance Regularization / Web Clock-In (shared fields — WEB_CLOCK_IN has no
    //    requestedCheckOut; attendanceDate/requestedCheckIn/regularizationReason double
    //    as workDate/requestedCheckIn/reason respectively) ──
    private LocalDate attendanceDate;
    private LocalDateTime requestedCheckIn;
    private LocalDateTime requestedCheckOut;
    private String regularizationReason;

    // ── Expense ───────────────────────────────────────────
    private String expenseCategoryName;
    private BigDecimal expenseAmount;
    private LocalDate expenseDate;
    private String businessPurpose;
    private String receiptUrl;
    // "MANAGER" = first stage (manager approves); "FINAL" = second stage (HR/SA clears)
    private String approvalStage;

    // ── Asset Request ─────────────────────────────────────
    private String requestedCategoryName;
    private String assetRequestReason;
    private String assetRequestStatus; // PENDING | APPROVED — drives Approval Center button set

    // ── Help Content (FAQ/Guide) — id above is the *attempt* id (what Approve/Reject act on),
    //    not the content id, since a content row may accumulate several attempts over time ──
    private String helpContentId;
    private String helpContentType; // FAQ | QUICK_HELP | GUIDE | DOCUMENT
    private String helpContentTitle;
    private String helpContentDescription;
    private String helpContentBody;
    private String helpContentCategory;
    private int helpContentAttemptNumber;
    private boolean helpContentModifiedSincePrevious;
}
