package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "expense_claims")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExpenseClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "category_id", nullable = false)
    private Integer categoryId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "business_purpose", nullable = false, columnDefinition = "TEXT")
    private String businessPurpose;

    // Stored as a base64 data: URI for local dev; replace with CDN URL when file storage is available.
    @Column(name = "receipt_url", columnDefinition = "TEXT")
    private String receiptUrl;

    // SUBMITTED | MANAGER_APPROVED | MANAGER_REJECTED | CLEARED_FOR_PAYROLL | FINAL_REJECTED | PAID
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "SUBMITTED";

    @Column(name = "manager_decided_by")
    private UUID managerDecidedBy;

    @Column(name = "manager_decided_at")
    private Instant managerDecidedAt;

    @Column(name = "manager_rejection_reason", columnDefinition = "TEXT")
    private String managerRejectionReason;

    @Column(name = "final_decided_by")
    private UUID finalDecidedBy;

    @Column(name = "final_decided_at")
    private Instant finalDecidedAt;

    @Column(name = "final_rejection_reason", columnDefinition = "TEXT")
    private String finalRejectionReason;

    @Column(name = "paid_at")
    private Instant paidAt;

    // Snapshotted by ExpenseService#submit at submission time from whatever Workflow Studio rule
    // (see ApprovalRuleEvaluationService) is active for EXPENSE right then — never re-derived
    // later, so an in-flight claim's approval requirements can't change out from under it if an
    // admin edits/activates/deactivates a rule after this claim was already submitted. Defaults
    // true (both stages) to match this app's one and only behavior before Workflow Studio existed.
    @Column(name = "requires_second_approval", nullable = false)
    @Builder.Default
    private boolean requiresSecondApproval = true;

    // Which rule produced the decision above, for audit/debugging — null when no rule was active
    // and the legacy default applied. No FK: a rule may later be deleted (only once inactive —
    // see ApprovalRuleService#delete) without breaking historical claims' audit trail.
    @Column(name = "evaluated_rule_id")
    private UUID evaluatedRuleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }
}
