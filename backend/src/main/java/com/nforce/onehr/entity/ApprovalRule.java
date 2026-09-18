package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Workflow Studio: a Super-Admin-configured, data-driven approval rule. Phase 1 supports exactly
 * one requestType ("EXPENSE") and one conditionField ("AMOUNT") — see ApprovalRuleService's
 * SUPPORTED_* constants for the extensibility seam Leave/Regularization/Asset will plug into later.
 *
 * <p>At most one rule may be {@code active} per {@code requestType} (see V184's partial unique
 * index) — activating a rule auto-deactivates whichever rule previously governed that type, so
 * "multiple active/conflicting rules" is structurally impossible rather than a runtime concern.
 */
@Entity
@Table(name = "approval_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApprovalRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rule_name", nullable = false, length = 150)
    private String ruleName;

    // Phase 1: "EXPENSE" only — see ApprovalRuleService.SUPPORTED_REQUEST_TYPES.
    @Column(name = "request_type", nullable = false, length = 30)
    private String requestType;

    // Phase 1: "AMOUNT" only — see ApprovalRuleService.SUPPORTED_CONDITION_FIELDS.
    @Column(name = "condition_field", nullable = false, length = 50)
    private String conditionField;

    // One of >, >=, <, <=, = — see ApprovalRuleService.SUPPORTED_OPERATORS.
    @Column(name = "operator", nullable = false, length = 5)
    private String operator;

    // Raw string so Phase 2 non-numeric condition fields don't need a schema change; Phase 1
    // (AMOUNT) validates this parses as a non-negative BigDecimal at write time.
    @Column(name = "condition_value", nullable = false, length = 50)
    private String conditionValue;

    // Comma-separated role codes required when the condition evaluates true, e.g.
    // "MANAGER,HR_ADMIN". Always starts with MANAGER — see ApprovalRuleService validation.
    @Column(name = "approval_stages", nullable = false, length = 100)
    private String approvalStages;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = false;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Concurrent-edit guard (Edge case #16) — same convention as RegularizationRequest (V171) /
    // Attendance (V164): the losing concurrent update() surfaces as a clean 409, not a silent
    // last-writer-wins overwrite of another admin's change.
    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
