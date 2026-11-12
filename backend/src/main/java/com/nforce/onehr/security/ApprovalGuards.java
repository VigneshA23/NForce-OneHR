package com.nforce.onehr.security;

import com.nforce.onehr.entity.User;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The one self-approval rule. An {@code *:APPROVE:ALL} override must never let someone decide their
 * own request: an approver is a second pair of eyes. Throw this BEFORE the permission/eligibility
 * checks so the override path cannot skip it. Workflows that already carry an equivalent guard
 * (leave, expense, help content, document verify) keep their own wording; everything else calls this.
 */
public final class ApprovalGuards {

    private ApprovalGuards() {}

    /**
     * The one rule for "may this person decide this request?": never your own; then PERMISSION and
     * ELIGIBILITY together — {@code <module>:APPROVE:ALL} (org-wide override), or the approver the request
     * was explicitly assigned to, or {@code <module>:APPROVE:TEAM} AND being the requester's current
     * reporting manager. A reporting line alone never authorizes; neither does a permission alone at TEAM
     * scope. Used by the workflows that share this shape (asset, WFH/partial-day, overtime).
     *
     * @param coverage   the actor's cascaded authorities ({@code EffectivePermissionService#coverage})
     * @param assignedApproverId the explicitly assigned approver, or null when the workflow has none
     * @param currentManagerOfRequester lazily asks the reporting line (only evaluated when needed)
     */
    public static void assertCanDecide(User actor, Set<String> coverage, String module, UUID requesterId,
                                       UUID assignedApproverId, BooleanSupplier currentManagerOfRequester,
                                       String what, String deniedMessage) {
        assertNotOwn(actor.getId(), requesterId, what);
        if (coverage.contains(module + ":APPROVE:ALL")) return;
        if (assignedApproverId != null && assignedApproverId.equals(actor.getId())) return;
        if (coverage.contains(module + ":APPROVE:TEAM") && currentManagerOfRequester.getAsBoolean()) return;
        throw new AccessDeniedException(deniedMessage);
    }

    /** @param what e.g. "regularization request" — used in the message, "You cannot decide on your own ...". */
    public static void assertNotOwn(UUID actorId, UUID requesterId, String what) {
        if (actorId != null && actorId.equals(requesterId)) {
            throw new AccessDeniedException("You cannot decide on your own " + what);
        }
    }
}
