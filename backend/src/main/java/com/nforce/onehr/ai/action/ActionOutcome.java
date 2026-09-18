package com.nforce.onehr.ai.action;

/** Terminal state of an attempted action. Present for the future contract; never produced today. */
public enum ActionOutcome {
    SUCCESS,
    /** Failed business-rule or input validation. */
    REJECTED,
    /** The caller is not permitted to perform this action. */
    UNAUTHORIZED,
    /** Requires an explicit user confirmation that has not been given. */
    CONFIRMATION_REQUIRED,
    /** Action execution is switched off - the only outcome reachable in this release. */
    DISABLED
}
