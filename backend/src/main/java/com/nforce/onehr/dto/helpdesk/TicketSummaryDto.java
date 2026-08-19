package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class TicketSummaryDto {
    private UUID id;
    private String ticketNumber;
    private String categoryName;
    private String status;
    private String priority;
    private UUID employeeUserId;
    private String employeeName;
    private UUID assignedTo;
    private String assignedToName;
    private long replyCount;
    private Instant createdAt;
    private Instant updatedAt;
}
