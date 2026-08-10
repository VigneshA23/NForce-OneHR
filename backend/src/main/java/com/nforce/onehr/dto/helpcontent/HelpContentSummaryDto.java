package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** List-view shape — used for both the employee-facing list and the HR admin list. */
@Data
@Builder
public class HelpContentSummaryDto {
    private UUID id;
    private String type;
    private String title;
    private String description;
    private String category;
    private boolean published;
    private boolean active;
    private boolean featured;
    private int displayOrder;
    private long viewCount;
    private boolean hasAttachment;
    private String attachmentName;
    private Instant createdAt;
    private Instant updatedAt;
}
