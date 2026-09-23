package com.nforce.onehr.ai.service;

import com.nforce.onehr.ai.dto.AiBillingResponse;
import com.nforce.onehr.ai.dto.AiBillingSettingsResponse;
import com.nforce.onehr.ai.dto.UpdateAiBillingSettingsRequest;
import com.nforce.onehr.ai.entity.AiBillingSettings;
import com.nforce.onehr.ai.entity.AiInteractionLog;
import com.nforce.onehr.ai.repository.AiBillingSettingsRepository;
import com.nforce.onehr.ai.repository.AiInteractionLogRepository;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Super-Admin-editable AI Assistant billing estimate — same singleton-row shape and
 * Super-Admin-only read+audited-write pattern as {@link AiRateLimitSettingsService}, minus that
 * service's enforcement cache: nothing on the chat request path reads this, only the Super-Admin
 * API Usage page does, so there is no hot-path cost to avoid.
 */
@Service
@RequiredArgsConstructor
public class AiBillingSettingsService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final AiBillingSettingsRepository repository;
    private final AiInteractionLogRepository interactionLogRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional(readOnly = true)
    public AiBillingSettingsResponse getForAdmin() {
        return AiBillingSettingsResponse.from(loadSingleton());
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public AiBillingSettingsResponse update(UpdateAiBillingSettingsRequest req, UUID actorId) {
        AiBillingSettings settings = loadSingleton();
        String before = auditSnapshot.toJson(Map.of(
                "monthlyBudgetUsd", settings.getMonthlyBudgetUsd(),
                "promptCostPerMillionUsd", settings.getPromptCostPerMillionUsd(),
                "completionCostPerMillionUsd", settings.getCompletionCostPerMillionUsd()));

        settings.setMonthlyBudgetUsd(req.getMonthlyBudgetUsd());
        settings.setPromptCostPerMillionUsd(req.getPromptCostPerMillionUsd());
        settings.setCompletionCostPerMillionUsd(req.getCompletionCostPerMillionUsd());
        settings = repository.save(settings);

        String after = auditSnapshot.toJson(Map.of(
                "monthlyBudgetUsd", settings.getMonthlyBudgetUsd(),
                "promptCostPerMillionUsd", settings.getPromptCostPerMillionUsd(),
                "completionCostPerMillionUsd", settings.getCompletionCostPerMillionUsd()));
        auditService.log(actorId, "AI_BILLING_SETTINGS_UPDATED", settings.getId(), before, after);

        return AiBillingSettingsResponse.from(settings);
    }

    /**
     * This calendar month to date (UTC), never a rolling 30-day window like the usage charts —
     * "billing" means the month a Mistral invoice would actually cover.
     */
    @Transactional(readOnly = true)
    public AiBillingResponse currentMonthBilling() {
        AiBillingSettings settings = loadSingleton();

        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate monthStart = YearMonth.from(today).atDay(1);
        Instant monthStartInstant = monthStart.atStartOfDay(ZoneOffset.UTC).toInstant();

        List<AiInteractionLog> logs = interactionLogRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(monthStartInstant, now);
        long promptTokens = 0, completionTokens = 0;
        for (AiInteractionLog log : logs) {
            promptTokens += log.getPromptTokens() == null ? 0 : log.getPromptTokens();
            completionTokens += log.getCompletionTokens() == null ? 0 : log.getCompletionTokens();
        }

        BigDecimal promptCost = BigDecimal.valueOf(promptTokens)
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP)
                .multiply(settings.getPromptCostPerMillionUsd());
        BigDecimal completionCost = BigDecimal.valueOf(completionTokens)
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP)
                .multiply(settings.getCompletionCostPerMillionUsd());
        BigDecimal estimatedCost = promptCost.add(completionCost).setScale(2, RoundingMode.HALF_UP);

        double usedPercent = settings.getMonthlyBudgetUsd().signum() == 0
                ? 0.0
                : estimatedCost.divide(settings.getMonthlyBudgetUsd(), 4, RoundingMode.HALF_UP).doubleValue() * 100;

        return AiBillingResponse.builder()
                .monthStart(monthStart)
                .today(today)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .monthlyBudgetUsd(settings.getMonthlyBudgetUsd())
                .estimatedCostUsd(estimatedCost)
                .usedPercent(usedPercent)
                .build();
    }

    private AiBillingSettings loadSingleton() {
        return repository.findBySingletonTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "AI Billing Settings row is missing — expected exactly one row seeded by migration V198"));
    }
}
