package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
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
    private String status;
    private Instant publishedAt;
    private boolean featured;
    private int displayOrder;
    private long viewCount;
    private List<AttachmentDto> attachments;
    private String rejectionReason;
    private String createdByName;
    private Instant createdAt;
    private String updatedByName;
    private Instant updatedAt;
}
