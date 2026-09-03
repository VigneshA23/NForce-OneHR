package com.nforce.onehr.config;

/**
 * Section 7: what happens to an employee with no current allocation and no legacy
 * {@code employees.penalisation_policy_id} FK. See {@link AttendanceProperties#getPenalizationFallbackStrategy()}
 * and {@code PenalizationPolicyResolutionService#resolveDefaultPolicyIdOrNull}.
 */
public enum PenalizationFallbackStrategy {

    /** The org's configured default policy (see {@code PenalisationPolicy#isOrgDefault()}) governs — the
     * long-standing, backward-compatible behavior. */
    DEFAULT_POLICY,

    /** No policy at all — the employee resolves to {@code null} and is never evaluated until an
     * explicit allocation exists. Surfaced as {@code resolvedPolicySource = "ALLOCATION_REQUIRED"}
     * on the Allocation screen so HR/admin can find and fix the gap. */
    REQUIRE_ALLOCATION
}
