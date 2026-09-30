package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.onboarding.OnboardingStatsDto;
import com.nforce.onehr.service.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** Onboarding progress in figures - the Onboarding page's summary tiles. */
public final class OnboardingDataProviders {

    private OnboardingDataProviders() {}

    /**
     * HR Admin only: the Onboarding page is in HR Admin's sidebar alone, and the audience mirrors
     * where the figures are actually shown, even though the endpoint also admits a Super Admin.
     */
    @Component
    @RequiredArgsConstructor
    public static class OnboardingSummary implements AssistantDataProvider {

        private final OnboardingService onboardingService;

        @Override public String id() { return "org-onboarding.summary"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Onboarding progress across new joiners"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR); }
        @Override public Set<String> modules() { return Set.of("onboarding"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            OnboardingStatsDto s = onboardingService.stats(context.getActorEmail());
            if (s == null) return Optional.empty();
            return Optional.of(("Onboarding: %d pending (not started), %d in progress, %d completed (%d completed this month), "
                    + "%d overdue. Average time to complete: %d day(s).").formatted(
                    s.getPendingCount(), s.getStartedCount(), s.getCompletedCount(), s.getCompletedThisMonthCount(),
                    s.getOverdueCount(), s.getAvgCompletionDays()));
        }
    }
}
