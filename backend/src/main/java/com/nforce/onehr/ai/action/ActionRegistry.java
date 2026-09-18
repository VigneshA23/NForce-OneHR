package com.nforce.onehr.ai.action;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The explicit list of actions the assistant knows about. It is empty, and emptying it is not a
 * configuration choice but the shipped state of the code.
 *
 * <p><strong>This class deliberately does not autowire {@code List<ActionDefinition>}.</strong>
 * Collecting beans by type is the idiomatic Spring way to build a registry, and it is exactly what
 * must not happen here: it would mean that adding any {@code @Component} implementing
 * {@link ActionDefinition} silently grants the assistant a new capability, with no diff anywhere
 * that reads like "the assistant can now do X". Registering an action must be a visible, reviewable
 * edit to the map below.
 *
 * <p>The class is {@code final} so the empty map cannot be reintroduced through a subclass, and
 * {@link #DEFINITIONS} is an immutable {@link Map#of()} so nothing can mutate it at runtime.
 *
 * <p>Populating this map still would not enable execution — see {@link DisabledActionExecutor}.
 * Both would have to change, in separate deliberate edits, for any action to become runnable.
 */
@Component
public final class ActionRegistry {

    /**
     * actionId to definition. Intentionally empty.
     *
     * <p>To add an action in a future release: implement {@link ActionDefinition}, add it here,
     * provide an {@link ActionAuthorization}, replace {@link DisabledActionExecutor} with an
     * implementation that delegates to the existing OneHR domain service for that operation, and
     * extend {@code ActionFrameworkDisabledTest} so the remaining actions stay proven inert.
     */
    private static final Map<String, ActionDefinition> DEFINITIONS = Map.of();

    public Optional<ActionDefinition> find(String actionId) {
        if (actionId == null) return Optional.empty();
        return Optional.ofNullable(DEFINITIONS.get(actionId));
    }

    /** Every registered action. Empty in this release. */
    public Collection<ActionDefinition> all() {
        return DEFINITIONS.values();
    }

    public boolean isRegistered(String actionId) {
        return find(actionId).isPresent();
    }

    /** True when no action is registered. Asserted by ActionFrameworkDisabledTest. */
    public boolean isEmpty() {
        return DEFINITIONS.isEmpty();
    }

    public int size() {
        return DEFINITIONS.size();
    }
}
