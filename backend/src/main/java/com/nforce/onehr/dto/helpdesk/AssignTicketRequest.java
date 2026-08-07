package com.nforce.onehr.dto.helpdesk;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignTicketRequest {

    @NotNull(message = "Assignee is required")
    private UUID assigneeUserId;
}
