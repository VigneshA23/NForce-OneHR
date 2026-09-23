package com.nforce.onehr.ai.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * This calendar month's estimated spend against the Super-Admin-configured budget — the progress
 * bar on the API Usage page. See {@code AiBillingSettings}'/V198's own comment on why this is an
 * estimate (token counts we actually track × a price the admin configures) rather than real
 * Mistral billing, which OneHR has no API access to.
 */
@Value
@Builder
public class AiBillingResponse {

    LocalDate monthStart;
    LocalDate today;

    long promptTokens;
    long completionTokens;

    BigDecimal monthlyBudgetUsd;
    BigDecimal estimatedCostUsd;
    /** 0-100+, uncapped so the UI can distinguish "at budget" from "over budget". */
    double usedPercent;
}
