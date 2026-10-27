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
    /** MANAGER / HR_ADMIN / SUPER_ADMIN — which role actually decided the Manager stage.
     * HR_ADMIN/SUPER_ADMIN means this was an override of the real reporting manager; null until
     * the Manager stage is decided. */
    private String managerApprovedByRole;
    private String finalDecidedByName;
    private Instant finalDecidedAt;
    private String finalRejectionReason;
    private Instant paidAt;
    private Instant createdAt;
    // Workflow Studio: whether this claim needs the HR/final stage at all, decided once at
    // submission time (see ExpenseClaim.requiresSecondApproval). false means Manager approval
    // alone clears it straight to CLEARED_FOR_PAYROLL.
    private boolean requiresSecondApproval;
    /** Role whose approval a MANAGER_APPROVED claim awaits next (HR_ADMIN/SUPER_ADMIN); null = either. */
    private String pendingFinalStage;
}
