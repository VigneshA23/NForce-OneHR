package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class TicketDetailDto {
    private UUID id;
    private String ticketNumber;
    private Integer categoryId;
    private String categoryName;
    private String description;
    private String status;
    private String priority;
    private UUID employeeUserId;
    private String employeeName;
    private UUID assignedTo;
    private String assignedToName;
    private Instant resolvedAt;
    private String resolvedByName;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ReplyDto> replies;
}
