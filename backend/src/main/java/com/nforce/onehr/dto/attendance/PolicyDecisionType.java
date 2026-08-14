package com.nforce.onehr.dto.attendance;

/**
 * Every outcome an {@link com.nforce.onehr.service.AttendancePolicyEngine} may return. Do not add
 * a fifth value without a corresponding product decision; the engine's whole point is that this
 * set is closed.
 */
public enum PolicyDecisionType {
    /** No policy covers this discrepancy — the common case while no policy is active. */
    NO_MATCH,
    /** A policy covers this discrepancy but explicitly excuses it (e.g. an approved exception). */
    EXEMPT,
    /** A policy matched and a penalty should be recorded. */
    APPLY_PENALTY,
    /** A policy would apply but is missing configuration needed to evaluate it. */
    CONFIGURATION_REQUIRED
}
