package com.nforce.onehr.ai.data;

/**
 * Whose records a live-data provider returns.
 *
 * <p>Stated to the model on every section, so "42 active users" is never read back to an admin as
 * though it were their own record, and checked against each provider's audiences by
 * {@code DataProviderSafetyTest}: the wider the scope, the narrower the set of roles allowed to run
 * it. That pairing is the whole safety argument for anything beyond {@link #SELF}.
 */
public enum DataScope {

    /** The signed-in user's own records. Any audience. */
    SELF("self", "the signed-in user's own records"),

    /**
     * Organisation content every signed-in user can already see in their own sidebar - published
     * announcements, the People Directory, upcoming birthdays. Any audience, because the UI serves
     * the same data to every role with no role check; never anything a role-gated screen holds.
     */
    SHARED("shared", "organisation content every signed-in user can see"),

    /**
     * The signed-in user's peers - everyone who shares their manager, themselves included - exactly
     * as My Team's peers view shows an employee. Any audience, because every employee sees this on
     * their own My Team page; always read through a service method that resolves the peer group
     * from the actor.
     */
    PEERS("peers", "colleagues who share the signed-in user's manager"),

    /** Items waiting on the signed-in user's decision, from actor-scoped approver queues. */
    APPROVALS("approvals", "items awaiting the signed-in user's decision"),

    /**
     * The signed-in user's direct reports, exactly as their Team pages show them. Never an Employee
     * audience; always read through a service method that resolves the team from the actor.
     */
    TEAM("team", "the signed-in user's direct reports"),

    /**
     * Organisation-wide figures. Only ever for the HR/Admin audiences whose own UI already shows the
     * same data, mirroring the {@code @PreAuthorize} on the controller that serves it - the service
     * methods behind org-wide screens usually take no actor at all, so the audience gate is the only
     * thing standing between them and everyone else.
     */
    ORGANISATION("organisation", "organisation-wide data");

    private final String code;
    private final String description;

    DataScope(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /** The value written into the prompt's {@code scope} attribute. */
    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}
