package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
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
    private String status;
    private boolean featured;
    private int displayOrder;
    private long viewCount;
    private int attachmentCount;
    // Empty/null = visible to everyone once published — see HelpContentService#publish.
    private List<String> audience;
    private String rejectionReason;
    private Instant createdAt;
    private Instant updatedAt;
}
