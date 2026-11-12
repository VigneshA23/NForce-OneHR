package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C24 follow-up (ONEHR-579) — a standing rule that applies the same {@code AD_HOC_EARNING}
 * component/amount/reason to every employee on every new or regenerated payroll run, instead of an
 * HR Admin re-adding the same recurring allowance (e.g. Food Allowance) by hand each cycle via
 * {@code AdHocPaymentService#add}/{@code #bulkAdd}. {@code payGroupId} null means every pay group;
 * set it to scope the rule to one.
 *
 * <p>Never mutated once created — deactivating and creating a replacement (rather than editing
 * {@code amount} in place) keeps a readable history of what applied and when, same convention
 * {@link com.nforce.onehr.entity.StatutoryRule} already uses for its own versioned configuration.
 *
 * <p>Applying a rule only ever creates {@link AdHocPayment} rows (see {@code
 * PayrollRunService#generateDraft}) — it never touches an already-generated payslip directly, and
 * a payslip already built before a rule existed/changed only sees it on its next regenerate, same
 * as any other DRAFT-only payroll input.
 */
@Entity
@Table(name = "recurring_ad_hoc_payment_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RecurringAdHocPaymentRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "pay_group_id")
    private UUID payGroupId;

    @Column(name = "component_id", nullable = false)
    private UUID componentId;

    @Column(name = "component_name", nullable = false, length = 50)
    private String componentName;

    @Column(name = "component_type", nullable = false, length = 20)
    private String componentType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
