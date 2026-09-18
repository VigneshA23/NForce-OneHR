package com.nforce.onehr.ai.action;

import com.nforce.onehr.ai.contract.AssistantRequestContext;

/**
 * Seam for deciding whether a caller may perform an action.
 *
 * <p>Has no implementation in this release. When one is added it must be a second gate in front of
 * the OneHR domain service, never a replacement for it: the domain service's own
 * {@code @PreAuthorize} and business rules stay authoritative, so an action can never grant access
 * a human user performing the same operation would not have.
 */
public interface ActionAuthorization {

    /**
     * @param definition the action being attempted
     * @param context    server-derived caller context, never client-supplied
     */
    boolean isAuthorized(ActionDefinition definition, AssistantRequestContext context);
}
