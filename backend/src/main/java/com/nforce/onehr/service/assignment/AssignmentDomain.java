package com.nforce.onehr.service.assignment;

import java.util.List;

/**
 * The policy domains the shared assignment-rule engine serves. PENALIZATION (PN-04) is the first;
 * LEAVE_PLAN (LV-01) and WFH_POLICY (WFH-02) register through the same {@link AssignmentTargetResolver}
 * hook - no second engine, no second rule table.
 */
public final class AssignmentDomain {

    public static final String PENALIZATION = "PENALIZATION";
    public static final String LEAVE_PLAN = "LEAVE_PLAN";
    public static final String WFH_POLICY = "WFH_POLICY";

    public static final List<String> ALL = List.of(PENALIZATION, LEAVE_PLAN, WFH_POLICY);

    private AssignmentDomain() {}
}
