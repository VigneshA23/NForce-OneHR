package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * One role-specific view of a navigable OneHR page, as declared in
 * {@code ai-knowledge/pages/registry.yaml}.
 *
 * <p>A page needs per-role variants because several OneHR routes are genuinely
 * role-polymorphic: {@code /requests} renders MyRequestsPage for Employee/Manager but
 * HelpDeskAdminPage for HR/Admin (and is labelled differently in each case), {@code /documents}
 * and {@code /audit} likewise. A single flat pageId -&gt; route -&gt; label mapping cannot express
 * that, so the registry stores one {@code PageReference} per (pageId, audience-set) variant.
 *
 * <p>{@code route} is carried for documentation and server-side sanity checks only. It is never
 * sent to the LLM and never returned to the browser as a URL — the frontend resolves the final
 * route itself from {@code nav.config.ts}, so the model can never invent a destination.
 */
@Data
@Builder
public class PageReference {

    /** Stable page id. For pages that appear in the sidebar this is exactly the nav.config.ts key. */
    private String pageId;

    /** Owning module id, used for retrieval boosting. */
    private String module;

    /** The label this page carries for this audience, e.g. "My Requests" vs "HR Service Requests". */
    private String label;

    /** The application route, e.g. "/requests". Documentation/validation only — never emitted as a URL. */
    private String route;

    /** What the user can do here, in one sentence. Fed to retrieval, not to route resolution. */
    private String description;

    /**
     * The shell roles this variant applies to, i.e. the roles whose NAV contains this page
     * with this label. Deliberately {@link ShellRole} and not {@link AudienceBucket}: the
     * sidebar is keyed by one role, so bucket-matching would authorise pages the user cannot
     * actually see. See {@link ShellRole} for the full reasoning.
     */
    private Set<ShellRole> roles;

    /**
     * True when this page is a roadmap placeholder (nav.config.ts {@code phase: 2} or
     * {@code locked}). Such pages are described but are never emitted as a navigation target.
     */
    private boolean placeholder;
}
