package com.nforce.onehr.dto.expense;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class ExpenseClaimResponse {
    private UUID id;
    private UUID employeeUserId;
    private String employeeName;
    private Integer categoryId;
    private String categoryName;
    private BigDecimal amount;
    private LocalDate expenseDate;
    private String businessPurpose;
    private String receiptUrl;
    private String status;
    private String managerDecidedByName;
    private Instant managerDecidedAt;
    private String managerRejectionReason;
    private String finalDecidedByName;
    private Instant finalDecidedAt;
    private String finalRejectionReason;
    private Instant paidAt;
    private Instant createdAt;
}
