package com.nforce.onehr.repository;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Every {@link com.nforce.onehr.entity.ExpenseClaim} column EXCEPT {@code receiptUrl} — that
 * column stores a full base64 data: URI (can be several MB) and is never read back from a list
 * response (receipts are always fetched on demand via the dedicated {@code GET
 * /claims/{id}/receipt} endpoint). Loading full {@code ExpenseClaim} entities for list endpoints
 * pulled that blob across the network from the DB for every row regardless of what got serialized
 * back out; this JPQL constructor-expression projection (see the repository's {@code findXSummary}
 * queries) stops the column being fetched at all for those call sites.
 */
@Getter
public class ExpenseClaimSummary {
    private final UUID id;
    private final UUID employeeUserId;
    private final Integer categoryId;
    private final BigDecimal amount;
    private final LocalDate expenseDate;
    private final String businessPurpose;
    private final String status;
    private final UUID managerDecidedBy;
    private final Instant managerDecidedAt;
    private final String managerRejectionReason;
    private final String managerApprovedByRole;
    private final UUID finalDecidedBy;
    private final Instant finalDecidedAt;
    private final String finalRejectionReason;
    private final Instant paidAt;
    private final Instant createdAt;
    private final boolean requiresSecondApproval;
    private final String pendingFinalStage;

    public ExpenseClaimSummary(UUID id, UUID employeeUserId, Integer categoryId, BigDecimal amount,
                                LocalDate expenseDate, String businessPurpose, String status,
                                UUID managerDecidedBy, Instant managerDecidedAt, String managerRejectionReason,
                                String managerApprovedByRole,
                                UUID finalDecidedBy, Instant finalDecidedAt, String finalRejectionReason,
                                Instant paidAt, Instant createdAt, boolean requiresSecondApproval,
                                String pendingFinalStage) {
        this.id = id;
        this.employeeUserId = employeeUserId;
        this.categoryId = categoryId;
        this.amount = amount;
        this.expenseDate = expenseDate;
        this.businessPurpose = businessPurpose;
        this.status = status;
        this.managerDecidedBy = managerDecidedBy;
        this.managerDecidedAt = managerDecidedAt;
        this.managerRejectionReason = managerRejectionReason;
        this.managerApprovedByRole = managerApprovedByRole;
        this.finalDecidedBy = finalDecidedBy;
        this.finalDecidedAt = finalDecidedAt;
        this.finalRejectionReason = finalRejectionReason;
        this.paidAt = paidAt;
        this.createdAt = createdAt;
        this.requiresSecondApproval = requiresSecondApproval;
        this.pendingFinalStage = pendingFinalStage;
    }
}
