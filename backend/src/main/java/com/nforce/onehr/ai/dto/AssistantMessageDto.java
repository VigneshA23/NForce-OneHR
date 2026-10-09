package com.nforce.onehr.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** One stored turn, for replaying a conversation into the UI. */
@Data
@Builder
public class AssistantMessageDto {
    /** USER or ASSISTANT. */
    private String sender;
    private String content;
    private String responseType;
    private Instant createdAt;
}
