package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class HelpContentDetailDto {
    private UUID id;
    private String type;
    private String title;
    private String description;
    private String body;
    private String category;
    private boolean published;
    private Instant publishedAt;
    private boolean active;
    private boolean featured;
    private int displayOrder;
    private long viewCount;
    private boolean hasAttachment;
    private String attachmentName;
    private String attachmentUrl;
    private String createdByName;
    private Instant createdAt;
    private String updatedByName;
    private Instant updatedAt;
}
