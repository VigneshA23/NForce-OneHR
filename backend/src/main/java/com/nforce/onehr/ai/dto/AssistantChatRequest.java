package com.nforce.onehr.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * The chat request body.
 *
 * <p>Note what it does NOT accept: no role, no permission, no userId, no route, no audience. There
 * is deliberately nowhere for a client to assert who it is or what it may see - all of that is
 * derived server-side from the authenticated principal, so a forged field has no field to forge.
 *
 * <p>Plain {@code @Data} with Jakarta validation, matching this codebase's request-DTO convention.
 */
@Data
public class AssistantChatRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 4000, message = "Message is too long")
    private String message;

    /** Prior conversation to continue. Unknown or someone else's id quietly starts a new one. */
    private String conversationId;

    /** The page the user is on. A retrieval hint only; re-validated against the page registry. */
    private String currentPageId;
}
