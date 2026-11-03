package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ContentEngagementDto {
    private UUID id;
    private String title;
    private String type;
    private long viewCount;
}
