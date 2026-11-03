package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SatisfactionStatsDto {
    /** Null if no ticket has ever been rated yet. */
    private Double averageRating;
    private long ratingCount;
    /** Share of ratings that are 4 or 5, as a percentage — 0 if ratingCount is 0. */
    private double percentSatisfied;
}
