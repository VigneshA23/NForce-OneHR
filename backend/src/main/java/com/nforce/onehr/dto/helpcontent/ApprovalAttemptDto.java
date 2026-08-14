package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One approval attempt as shown to an approver (Approval Center) or an author (history). */
@Data
@Builder
public class ApprovalAttemptDto {
    private UUID id;
    private UUID contentId;
    private String contentType;
    private String contentTitle;
    private int attemptNumber;
    private UUID submittedByUserId;
    private String submittedByName;
    private Instant submittedAt;
    private String approverName;
    private String status; // PENDING | APPROVED | REJECTED | WITHDRAWN
    private Instant decidedAt;
    private String rejectionReason;
    private String withdrawalReason;
    private String snapshotTitle;
    private String snapshotDescription;
    private String snapshotBody;
    private String snapshotCategory;
    private boolean snapshotFeatured;
    private int snapshotDisplayOrder;
    private List<AttachmentDto> attachments;
    private boolean modifiedSincePrevious;
}
