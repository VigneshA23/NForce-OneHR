package com.nforce.onehr.ai.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * The four UI roles the OneHR sidebar is keyed by, mirroring the frontend union in
 * {@code nav.config.ts} ({@code Employee | Manager | HR Admin | Super Admin}).
 *
 * <p><strong>Why this exists alongside {@link AudienceBucket}, which has the same four values:</strong>
 * they answer different questions and are not interchangeable.
 *
 * <ul>
 *   <li>{@link AudienceBucket} is a <em>set</em> derived from every role a user really holds
 *       ({@code RoleUtils.audienceBuckets}). An HR Admin genuinely holds HR <em>and</em> EMPLOYEE,
 *       so their bucket set is {@code {HR, EMPLOYEE\}}. That is the right filter for knowledge
 *       retrieval, because it mirrors how Help and Guidance decides who may read an article.</li>
 *   <li>{@code ShellRole} is a <em>single</em> value chosen by priority. The frontend renders
 *       {@code NAV[toShellRole(user.role)]} and nothing else, so a page is reachable only if it is
 *       in that one role's nav list.</li>
 * </ul>
 *
 * <p>Using buckets to authorise navigation would be a real bug, not a nuance: an HR Admin carries
 * the EMPLOYEE bucket, so any page tagged EMPLOYEE would look reachable to them. A Super Admin
 * (buckets {@code {ADMIN, EMPLOYEE\}}) would be offered {@code my-team}, which is in the Employee,
 * Manager and HR Admin navs but <em>not</em> theirs. The assistant would emit a navigation action
 * the sidebar cannot honour, and because Shell falls back to the dashboard nav item for an unknown
 * path and still renders the outlet, the user would land on a page with no matching nav entry.
 *
 * <p>So: retrieval filters on buckets; navigation authorises on {@code ShellRole}.
 */
public enum ShellRole {
    EMPLOYEE("Employee"),
    MANAGER("Manager"),
    HR_ADMIN("HR Admin"),
    SUPER_ADMIN("Super Admin");

    private final String label;

    ShellRole(String label) {
        this.label = label;
    }

    /** The role's name as the sidebar and the rest of OneHR write it. */
    public String label() {
        return label;
    }

    /**
     * Server-side mirror of {@code nav.config.ts#toShellRole}.
     *
     * <p>The input must be the same value the login response puts on the client auth store, which
     * {@code AuthService} builds with {@code RoleUtils.primaryRoleCode(user.getRoles(), "EMPLOYEE")}.
     * Deriving it any other way would let the backend and the sidebar disagree about which nav the
     * user is actually looking at.
     *
     * <p>LEADERSHIP, FINANCE and DELIVERY collapse to EMPLOYEE, exactly as the frontend does, and
     * an unknown or null code collapses the same way — failing closed to the least-privileged nav.
     */
    public static ShellRole fromPrimaryRoleCode(String primaryRoleCode) {
        if (primaryRoleCode == null) return EMPLOYEE;
        switch (primaryRoleCode.trim().toUpperCase()) {
            case "SUPER_ADMIN": return SUPER_ADMIN;
            case "HR_ADMIN":    return HR_ADMIN;
            case "MANAGER":     return MANAGER;
            default:            return EMPLOYEE;
        }
    }

    public static Optional<ShellRole> fromCode(String code) {
        if (code == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(r -> r.name().equalsIgnoreCase(code.trim()))
                .findFirst();
    }
}
