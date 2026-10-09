package com.nforce.onehr.ai.action;

import lombok.Builder;
import lombok.Data;

/** One declared input of an {@link ActionDefinition}. */
@Data
@Builder
public class ActionParameter {

    private String name;

    /** Logical type (STRING, DATE, UUID, ENUM...), kept as a string so it stays storage-neutral. */
    private String type;

    private String description;

    private boolean required;

    /**
     * Human-readable validation rule. Documentation for a future implementer only. The real rule
     * must stay in the OneHR domain service that eventually performs the action, never be
     * re-expressed here where it could drift from the behaviour a human user gets.
     */
    private String validationRule;
}
