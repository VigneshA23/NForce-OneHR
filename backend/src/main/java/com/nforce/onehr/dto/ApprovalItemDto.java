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
 * requestType discriminator: LEAVE | REGULARIZATION | EXPENSE | ASSET_REQUEST
 */
@Data
@Builder
public class ApprovalItemDto {

    private String id;          // String to cover UUID (Leave/Expense) and Long (Regularization/AssetRequest)
    private String requestType; // LEAVE | REGULARIZATION | EXPENSE | ASSET_REQUEST
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

    // ── Attendance Regularization ─────────────────────────
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
}
