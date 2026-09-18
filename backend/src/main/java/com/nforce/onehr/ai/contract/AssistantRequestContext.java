package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

import java.util.Set;
import java.util.UUID;

/**
 * Everything the assistant is allowed to know about who is asking. Built entirely server-side from
 * the authenticated principal — nothing here is ever read from the request body.
 *
 * <p>Note what is absent: no email, no name, no employee record. Identity beyond {@code userId}
 * (which is used for rate limiting, conversation ownership and interaction logging, and is never
 * placed in a prompt) is not needed to answer a "how do I…" question, so it is not collected.
 */
@Data
@Builder
public class AssistantRequestContext {

    /** Authenticated user. Used for ownership/limits/logging only — never sent to the provider. */
    private UUID userId;

    /**
     * Highest-priority role code held, from {@code RoleUtils#primaryRoleCode} — one of the seven
     * real codes (SUPER_ADMIN, HR_ADMIN, MANAGER, LEADERSHIP, FINANCE, DELIVERY, EMPLOYEE). Used
     * to phrase answers ("as a Manager you can…").
     */
    private String primaryRoleCode;

    /**
     * The single UI role whose sidebar this user actually sees, mirroring
     * {@code nav.config.ts#toShellRole}. This, not {@link #audiences}, is what authorises a
     * navigation target: the frontend renders NAV for exactly one role, so a page is reachable
     * only if it appears in that role's list. See {@link ShellRole} for why the two must not be
     * conflated.
     */
    private ShellRole shellRole;

    /**
     * The caller's audience buckets from {@code RoleUtils#audienceBuckets}. This is the security
     * boundary for retrieval and for navigation authorisation — not {@link #primaryRoleCode},
     * because a Manager legitimately holds both MANAGER and EMPLOYEE.
     */
    private Set<AudienceBucket> audiences;

    /**
     * Page the user is currently on, if the client supplied one. A retrieval hint only: it is
     * re-validated against the page registry and silently dropped if unknown or not reachable by
     * this caller, so a spoofed value can bias ranking at worst and can never widen access.
     */
    private String currentPageId;

    /** Module owning {@link #currentPageId}, resolved server-side from the registry. */
    private String currentModule;
}
