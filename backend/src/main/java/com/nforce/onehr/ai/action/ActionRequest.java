package com.nforce.onehr.ai.action;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * A structured request to perform an action. Never constructed from raw model output in this
 * release, and no HTTP endpoint accepts one.
 *
 * <p>Note what this type does <em>not</em> carry: no user id, no role, no permission, no tenant.
 * That omission is the design. A future executor must re-derive the caller from the authenticated
 * principal, so that even a perfectly well-formed request originating from the model or a client
 * cannot assert who it is acting as.
 */
@Data
@Builder
public class ActionRequest {

    /** The {@link ActionDefinition#actionId()} being requested. */
    private String actionId;

    /** Caller-supplied inputs, all untrusted and all subject to domain-service validation. */
    private Map<String, Object> parameters;

    /**
     * Echoed back from an {@link ActionConfirmation} the user explicitly accepted. Absent or stale
     * means the action has not been confirmed.
     */
    private String confirmationToken;

    /** Conversation this request arose from, for traceability. */
    private String conversationId;
}
