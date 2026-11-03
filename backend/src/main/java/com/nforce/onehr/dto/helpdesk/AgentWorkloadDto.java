package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AgentWorkloadDto {
    private UUID agentId;
    private String agentName;
    /** Currently OPEN or IN_PROGRESS tickets assigned to them right now. */
    private long activeTicketCount;
    /** Across every ticket they've ever resolved — null if they've never resolved one. */
    private Double averageResolutionHours;
}
