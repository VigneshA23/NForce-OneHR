package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

import java.util.Set;
import java.util.UUID;

/**
 * Everything the assistant is allowed to know about who is asking. Built entirely server-side from
 * the authenticated principal — nothing here is ever read from the request body.
 *
 * <p>Note what is absent beyond {@link #actorEmail} and {@link #actorName}: no employee record, no
 * contact, identity or bank detail. {@code userId} itself is used for rate limiting, conversation
 * ownership and interaction logging, and is never placed in a prompt.
 */
@Data
@Builder
public class AssistantRequestContext {

    /** Authenticated user. Used for ownership/limits/logging only — never sent to the provider. */
    private UUID userId;

    /**
     * The authenticated principal's email.
     *
     * <p>Present so live-data providers can call the existing actor-scoped service methods, which
     * all take an email and resolve the caller themselves. It always comes from the JWT via
     * {@code principal.getName()} and never from a request body, so it cannot be used to read
     * somebody else's records.
     */
    private String actorEmail;

    /**
     * The authenticated principal's own full name, from their employee record, or null when none
     * exists yet.
     *
     * <p>Exists so the model can recognise a third-person or first-name self-reference ("tell me
     * about Praveen" asked by Praveen Gurram himself) without that turning into a lookup of someone
     * else (ONEHR - AI chatbot fails to handle duplicate employee names). It is placed in the prompt
     * for exactly that comparison and nothing else - it is not, on its own, authorisation to read or
     * name anyone but the caller.
     */
    private String actorName;

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
