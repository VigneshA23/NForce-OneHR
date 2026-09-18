package com.nforce.onehr.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Thumbs up or down on an answer, with optional free-text detail. */
@Data
public class AssistantFeedbackRequest {

    @NotBlank(message = "Conversation is required")
    private String conversationId;

    /** UP or DOWN. */
    @NotBlank(message = "Rating is required")
    private String rating;

    @Size(max = 1000, message = "Comment is too long")
    private String comment;
}
