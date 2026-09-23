package com.nforce.onehr.ai.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * The Super-Admin "API Usage" dashboard — answers this feature's two acceptance criteria directly:
 * {@link #daily} is "request volume and token usage over a period" (AC1), and {@link #byErrorCode}
 * is "a spike is traceable to a cause rather than opaque" (AC2) — a jump in requests next to a jump
 * in {@code RATE_LIMITED} or {@code PROVIDER_UNAVAILABLE} tells a different story than a jump next
 * to a flat error line.
 *
 * <p>No cost/$ figure is included — {@code ai_interaction_log} tracks prompt/completion token
 * counts, never a dollar amount (Mistral's own console is the source of truth for pricing, which is
 * exactly why this page also links out to it rather than trying to re-derive a cost figure that
 * could drift from what's actually billed).
 */
@Value
@Builder
public class AiUsageStatsResponse {

    Instant from;
    Instant to;

    int totalRequests;
    int successCount;
    int errorCount;
    long totalPromptTokens;
    long totalCompletionTokens;
    long totalTokens;
    /** 0 when {@code totalRequests} is 0 — never NaN. */
    double avgLatencyMs;

    List<AiUsageDailyPoint> daily;
    /** Only turns where {@code success == false}; sorted by count descending. */
    List<AiUsageBreakdownPoint> byErrorCode;
    List<AiUsageBreakdownPoint> byResponseType;
}
