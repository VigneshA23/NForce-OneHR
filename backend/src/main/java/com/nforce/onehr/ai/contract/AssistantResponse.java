package com.nforce.onehr.ai.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The OneHR-owned response contract. This is both what the LLM is asked to produce and what the
 * frontend receives — but never the same instance: raw model output is deserialized here, then
 * {@code ResponseValidator} rebuilds a clean instance before anything leaves the backend.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is load-bearing, not hygiene. It is the
 * structural reason a model that invents an {@code "action"} / {@code "url"} / {@code "sql"} field
 * cannot smuggle it through: unknown fields are dropped at parse time rather than reaching any
 * code that might act on them. See {@code com.nforce.onehr.ai.action} for the rest of that story.
 *
 * <p>There is deliberately no action field in this release.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantResponse {

    private AssistantResponseType type;

    /** The prose answer. Rendered by the frontend as pre-wrapped plain text, never as markdown/HTML. */
    private String answer;

    /** Ordered steps for a HOW_TO. Empty for other types. */
    private List<String> steps;

    /** Present only when a page was validated as reachable by this caller. */
    private NavigationAction navigation;

    private List<RelatedItem> related;

    private ConfidenceLevel confidence;

    /**
     * Conversation this turn belongs to, so the client can send it back on the next message.
     * Server-assigned; any value supplied by the model is discarded.
     */
    private String conversationId;
}
