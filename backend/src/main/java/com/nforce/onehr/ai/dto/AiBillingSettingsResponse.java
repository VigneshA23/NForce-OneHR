package com.nforce.onehr.ai.dto;

import com.nforce.onehr.ai.entity.AiBillingSettings;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class AiBillingSettingsResponse {

    UUID id;
    BigDecimal monthlyBudgetUsd;
    BigDecimal promptCostPerMillionUsd;
    BigDecimal completionCostPerMillionUsd;
    BigDecimal embeddingCostPerMillionUsd;
    Instant updatedAt;

    public static AiBillingSettingsResponse from(AiBillingSettings settings) {
        return AiBillingSettingsResponse.builder()
                .id(settings.getId())
                .monthlyBudgetUsd(settings.getMonthlyBudgetUsd())
                .promptCostPerMillionUsd(settings.getPromptCostPerMillionUsd())
                .completionCostPerMillionUsd(settings.getCompletionCostPerMillionUsd())
                .embeddingCostPerMillionUsd(settings.getEmbeddingCostPerMillionUsd())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }
}
