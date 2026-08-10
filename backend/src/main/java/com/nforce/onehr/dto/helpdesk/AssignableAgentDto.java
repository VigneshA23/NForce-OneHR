package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AssignableAgentDto {
    private UUID userId;
    private String name;
}
