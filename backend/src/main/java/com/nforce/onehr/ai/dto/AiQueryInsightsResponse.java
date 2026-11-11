package com.nforce.onehr.ai.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * NORA Query Insights for a date range (inclusive, UTC days). Counts cover question outcomes only;
 * technical failures and guard declines have no outcome and are in none of these numbers.
 * {@code matchRate} is matched / total, 0 when nothing was asked.
 */
public record AiQueryInsightsResponse(
        LocalDate from,
        LocalDate to,
        long total,
        long matched,
        long ambiguous,
        long unmatched,
        double matchRate,
        List<UnmatchedPhrase> topUnmatched) {

    public record UnmatchedPhrase(String phrase, long occurrences, Instant lastAsked) {}
}
