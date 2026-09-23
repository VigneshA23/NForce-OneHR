package com.nforce.onehr.ai.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;

/** One calendar day's slice of {@link AiUsageStatsResponse#getDaily()} — the request-volume/token chart. */
@Value
@Builder
public class AiUsageDailyPoint {

    LocalDate date;
    int requestCount;
    int successCount;
    int errorCount;
    long promptTokens;
    long completionTokens;
}
