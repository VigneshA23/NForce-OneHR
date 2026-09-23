package com.nforce.onehr.ai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Bounds mirror {@code AiBillingSettingsService}'s own validation and V198's DB-level CHECKs —
 * same belt-and-suspenders as {@code UpdateAiRateLimitSettingsRequest}.
 */
@Data
public class UpdateAiBillingSettingsRequest {

    @NotNull(message = "monthlyBudgetUsd is required")
    @DecimalMin(value = "0", message = "monthlyBudgetUsd cannot be negative")
    private BigDecimal monthlyBudgetUsd;

    @NotNull(message = "promptCostPerMillionUsd is required")
    @DecimalMin(value = "0", message = "promptCostPerMillionUsd cannot be negative")
    private BigDecimal promptCostPerMillionUsd;

    @NotNull(message = "completionCostPerMillionUsd is required")
    @DecimalMin(value = "0", message = "completionCostPerMillionUsd cannot be negative")
    private BigDecimal completionCostPerMillionUsd;
}
