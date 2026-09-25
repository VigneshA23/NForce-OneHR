package com.nforce.onehr.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The Super-Admin-editable AI Assistant billing estimate — a singleton row, same
 * UNIQUE/CHECK(singleton) enforcement as {@link AiRateLimitSettings} (see V198's own migration
 * comment for why OneHR estimates rather than reads real Mistral billing).
 */
@Entity
@Table(name = "ai_billing_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiBillingSettings {

    @Id
    @GeneratedValue
    private UUID id;

    // Always true — see the class Javadoc. Never toggled by application code; its only purpose is
    // the DB-level UNIQUE constraint that makes a second row impossible.
    @Column(nullable = false)
    @Builder.Default
    private boolean singleton = true;

    @Column(name = "monthly_budget_usd", nullable = false)
    private BigDecimal monthlyBudgetUsd;

    @Column(name = "prompt_cost_per_million_usd", nullable = false)
    private BigDecimal promptCostPerMillionUsd;

    @Column(name = "completion_cost_per_million_usd", nullable = false)
    private BigDecimal completionCostPerMillionUsd;

    /** Priced separately from prompt/completion — mistral-embed is typically the cheapest of the
     *  three, and was previously not billed for at all since its tokens weren't tracked (see V199). */
    @Column(name = "embedding_cost_per_million_usd", nullable = false)
    private BigDecimal embeddingCostPerMillionUsd;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }
}
