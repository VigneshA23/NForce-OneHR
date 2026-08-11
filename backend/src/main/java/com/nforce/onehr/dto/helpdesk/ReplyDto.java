package com.nforce.onehr.dto.helpdesk;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ReplyDto {
    private UUID id;
    private UUID senderId;
    private String senderName;
    private String senderRole;   // EMPLOYEE | HR
    private String message;
    private boolean internal;
    private boolean hasAttachment;
    private String attachmentName;
    private String attachmentUrl;
    private Instant createdAt;
}
