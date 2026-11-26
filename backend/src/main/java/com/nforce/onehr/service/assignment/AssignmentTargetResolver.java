package com.nforce.onehr.service.assignment;

import java.util.Optional;
import java.util.UUID;

/**
 * Plug-in point of the shared rule engine: one bean per policy domain tells the engine what a rule
 * of that domain may point at. A new domain (leave plans, WFH policies) adds one implementation of
 * this interface and calls the engine for resolution - it does not add rule storage or matching.
 */
public interface AssignmentTargetResolver {

    /** One of {@link AssignmentDomain}. */
    String domain();

    /** Permission module guarding this domain's rules, e.g. "penalization" -> penalization:EDIT:ALL. */
    String permissionModule();

    /** True when the target exists and is usable (e.g. an ACTIVE policy) - unusable targets never match. */
    boolean isUsable(UUID targetId);

    /** Display name of the target policy, for the rule list and the "why" explanation. */
    Optional<String> targetName(UUID targetId);
}
