package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.dto.AiBillingResponse;
import com.nforce.onehr.ai.dto.AiUsageStatsResponse;
import com.nforce.onehr.ai.service.AiBillingSettingsService;
import com.nforce.onehr.ai.service.AiUsageStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The assistant's own usage and estimated spend - the Super Admin's API Usage page. */
public final class AiUsageDataProviders {

    private AiUsageDataProviders() {}

    /**
     * Super Admin only, matching the {@code hasRole('SUPER_ADMIN')} on the API Usage endpoints.
     * Figures use the same US number formatting as that page.
     */
    @Component
    @RequiredArgsConstructor
    public static class ApiUsage implements AssistantDataProvider {

        private static final int WINDOW_DAYS = 30;

        private final AiUsageStatsService usageStatsService;
        private final AiBillingSettingsService billingSettingsService;

        @Override public String id() { return "org-ai-usage.summary"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "AI assistant usage and estimated spend"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("api-usage"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            NumberFormat n = NumberFormat.getIntegerInstance(Locale.US);
            StringBuilder out = new StringBuilder();
            AiUsageStatsResponse stats = usageStatsService.stats(WINDOW_DAYS);
            if (stats != null) {
                out.append("Last %d days: %s assistant turns (%s successful, %s errors), %s API requests, %s tokens in total, average latency %s ms."
                        .formatted(WINDOW_DAYS, n.format(stats.getTotalTurns()), n.format(stats.getSuccessCount()),
                                n.format(stats.getErrorCount()), n.format(stats.getTotalRequests()), n.format(stats.getTotalTokens()),
                                n.format(Math.round(stats.getAvgLatencyMs()))));
            }
            AiBillingResponse billing = billingSettingsService.currentMonthBilling();
            if (billing != null) {
                out.append("\nThis month (%s to %s): estimated cost $%s of a $%s monthly budget (%.1f%% used); %s prompt, %s completion and %s embedding tokens. This is an estimate from OneHR's own token logs, not the provider's invoice."
                        .formatted(billing.getMonthStart(), billing.getToday(), billing.getEstimatedCostUsd(),
                                billing.getMonthlyBudgetUsd(), billing.getUsedPercent(), n.format(billing.getPromptTokens()),
                                n.format(billing.getCompletionTokens()), n.format(billing.getEmbeddingTokens())));
            }
            String text = out.toString().strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }
    }
}
