package com.nforce.onehr.ai.dto;

import lombok.Builder;
import lombok.Value;

/**
 * One category's share of the window — backs both the error-code and response-type breakdowns on
 * {@link AiUsageStatsResponse}. The same shape for both since a chart/legend renders either
 * identically; {@code key} is whichever category the caller asked for (an {@code errorCode} like
 * {@code RATE_LIMITED}, or a {@code responseType} like {@code HOW_TO}).
 */
@Value
@Builder
public class AiUsageBreakdownPoint {

    String key;
    long count;
}
