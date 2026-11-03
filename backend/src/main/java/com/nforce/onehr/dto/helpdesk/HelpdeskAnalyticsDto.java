package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** HR-only — what Help Desk's raw 4-count dashboard doesn't show: what people ask about most,
 *  how fast tickets get resolved, how agent workload breaks down, how satisfied employees say
 *  they are, and which published Help Content is actually getting read (or isn't). */
@Data
@Builder
public class HelpdeskAnalyticsDto {
    private List<CategoryCountDto> categoryBreakdown;
    /** Null if no ticket has ever reached RESOLVED yet. */
    private Double averageResolutionHours;
    private List<AgentWorkloadDto> agentWorkload;
    private SatisfactionStatsDto satisfaction;
    /** Published Help Content, most-viewed first, capped at 5. */
    private List<ContentEngagementDto> topContent;
    /** Published Help Content with zero views — candidates to revise, feature, or archive. Capped at 10. */
    private List<ContentEngagementDto> zeroViewContent;
}
