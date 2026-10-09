package com.nforce.onehr.ai.contract;

/**
 * The OneHR-owned response categories. Deliberately provider-neutral: the LLM is asked to pick one
 * of these, and {@code ResponseValidator} coerces anything it doesn't recognise to {@link #UNKNOWN}
 * rather than passing an unvetted value through to the frontend.
 *
 * <p>There is intentionally no action/mutation category in this release — see
 * {@code com.nforce.onehr.ai.action} for why the action layer is structurally present but inert.
 */
public enum AssistantResponseType {
    /** Step-by-step instructions for performing a supported OneHR action. */
    HOW_TO,
    /** Conceptual explanation of a module, page, term or workflow. */
    EXPLANATION,
    /** The answer is primarily "go here" — carries a validated {@link NavigationAction}. */
    NAVIGATION,
    /** Explains a known error or failure and how to resolve it. */
    TROUBLESHOOTING,
    /** Explains who can do something, or why the user cannot. */
    PERMISSION,
    /** Not enough authorised OneHR knowledge to answer. Never a guess. */
    UNKNOWN
}
