package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Unified "my own submissions" item returned by the My Requests endpoint.
 * Read-only tracking view — no approve/reject decisions happen here (those
 * belong exclusively to the Approval Center, see ApprovalItemDto).
 *
 * requestType discriminator: LEAVE | REGULARIZATION | EXPENSE | ASSET_REQUEST
 */
@Data
@Builder
public class MyRequestItemDto {

    private String id;          // String to cover UUID (Leave/Regularization/Expense) and Long (AssetRequest)
    private String requestType; // LEAVE | REGULARIZATION | EXPENSE | ASSET_REQUEST
    private UUID employeeUserId;
    private String employeeName;
    private Instant createdAt;

    // ── Unified status/decision fields (verbatim per-type status; decision fields
    //    collapsed from each type's own naming — see MyRequestsController mappers) ──
    private String status;
    private String decisionReason;
    private String decidedByName;
    private Instant decidedAt;

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

    // ── Asset Request ─────────────────────────────────────
    private String requestedCategoryName;
    private String assetRequestReason;
}
