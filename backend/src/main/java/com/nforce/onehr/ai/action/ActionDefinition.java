package com.nforce.onehr.ai.action;

import java.util.List;
import java.util.Set;

/**
 * Metadata describing an action the assistant could one day perform on the user's behalf.
 *
 * <p><strong>Deliberately has no execute method.</strong> A definition describes an action; it
 * cannot perform one. Execution lives behind {@link ActionExecutor}, so holding a definition,
 * however it was obtained, grants no ability to mutate anything.
 *
 * <p>There are no implementations of this interface in src/main, and {@link ActionRegistry} holds
 * none. See this package's package-info for the full set of inertness guarantees.
 */
public interface ActionDefinition {

    /** Stable unique id, e.g. leave.apply. */
    String actionId();

    String name();

    String description();

    /**
     * Role codes permitted to perform this action, as OneHR's bare codes (EMPLOYEE, MANAGER,
     * HR_ADMIN, SUPER_ADMIN, ...). Advisory metadata for display and filtering only:
     * {@link ActionAuthorization} and the underlying domain service remain authoritative, and
     * neither may trust a value that reached the backend from the model or the client.
     */
    Set<String> requiredRoles();

    List<ActionParameter> parameters();

    /** Conditions that must already hold, in human-readable form. */
    List<String> preconditions();

    /** Whether the user must explicitly confirm before this runs. */
    boolean requiresConfirmation();

    /** What the user should expect to have happened afterwards. */
    String expectedResult();

    String module();

    String pageId();

    String workflowId();

    /**
     * Per-action kill switch for a future release, so actions can be turned on one at a time once
     * each is individually implemented, permission-checked and tested.
     *
     * <p>This being true still would not make an action runnable today:
     * {@link DisabledActionExecutor} ignores it and refuses unconditionally.
     */
    boolean enabled();
}
