package com.nforce.onehr.dto.onboarding;

import lombok.Builder;
import lombok.Data;

/**
 * Aggregate KPI counts for the Onboarding page header cards — computed once across the whole
 * corpus server-side (same computation OnboardingService#listQueue/#eligibleEmployees already
 * did) and returned as a handful of numbers, independent of whichever tab/page/search the lists
 * themselves are currently showing.
 */
@Data @Builder
public class OnboardingStatsDto {
    private long pendingCount;
    private long startedCount;
    private long completedCount;
    private long overdueCount;
    private long completedThisMonthCount;
    private long avgCompletionDays;
}
