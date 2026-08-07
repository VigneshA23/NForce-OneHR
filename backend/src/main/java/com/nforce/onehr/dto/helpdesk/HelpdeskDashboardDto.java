package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HelpdeskDashboardDto {
    private long openCount;
    private long assignedCount;
    private long inProgressCount;
    private long waitingForEmployeeCount;
    private long resolvedCount;
    private long closedCount;
}
