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
 * <p><strong>{@link #totalRequests} counts real Mistral HTTP requests, not assistant turns.</strong>
 * One turn (one question) costs at least two real requests — an embedding call for retrieval, then
 * a chat completion call — plus any transport-level retries; see {@link
 * com.nforce.onehr.ai.entity.AiInteractionLog#getApiCallAttempts()}. {@link #totalTurns} is the
 * older, simpler "how many questions were asked" figure, kept alongside it since both are useful.
 * Knowledge reindexing calls Mistral directly too but isn't tied to a turn, so it's still outside
 * both figures — the page's own footer note says so.
 *
 * <p>No cost/$ figure is included here — {@code ai_interaction_log} tracks token counts, never a
 * dollar amount (Mistral's own console is the source of truth for pricing). See {@code
 * AiBillingResponse} for the separate, admin-configured estimate that does turn these tokens into a
 * dollar figure.
 */
@Value
@Builder
public class AiUsageStatsResponse {

    Instant from;
    Instant to;

    /** Real Mistral HTTP requests (embedding + completion attempts, including retries) — see class javadoc. */
    long totalRequests;
    /** Assistant turns (questions asked) — the older, coarser figure {@link #totalRequests} replaces as the headline. */
    int totalTurns;
    int successCount;
    int errorCount;
    long totalPromptTokens;
    long totalCompletionTokens;
    /** The embedding call's own token usage — previously not captured at all. */
    long totalEmbeddingTokens;
    long totalTokens;
    /** 0 when {@code totalTurns} is 0 — never NaN. */
    double avgLatencyMs;

    List<AiUsageDailyPoint> daily;
    /** Only turns where {@code success == false}; sorted by count descending. */
    List<AiUsageBreakdownPoint> byErrorCode;
    List<AiUsageBreakdownPoint> byResponseType;
}
