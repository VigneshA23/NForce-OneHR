package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
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
     * Behind the Onboarding page's own grant ({@code onboarding:VIEW:ALL}), but - as before - not for
     * a Super Admin: the page is not in their sidebar, so the figures follow where they are actually
     * shown, even though the endpoint also admits them.
     *
     * <p>ponytail: "not a Super Admin" is expressed as standing aside for user_management:VIEW:ALL
     * (seeded for Super Admin alone), so a custom role holding both grants also loses these figures;
     * gate on the caller's reachable pages instead if that combination ever matters.
     */
    @Component
    @RequiredArgsConstructor
    public static class OnboardingSummary implements AssistantDataProvider {

        private final OnboardingService onboardingService;

        @Override public String id() { return "org-onboarding.summary"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Onboarding progress across new joiners"; }
        @Override public Set<String> authorities() { return Set.of("onboarding:VIEW:ALL"); }
        @Override public Set<String> standsAsideFor() { return Set.of(OrganisationDataProviders.USER_MANAGEMENT); }
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
